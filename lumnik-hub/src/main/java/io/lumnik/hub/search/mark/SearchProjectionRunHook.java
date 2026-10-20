package io.lumnik.hub.search.mark;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.connector.event.ConnectorRunCompleted;
import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.entity.EntityManifestParser;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.entity.EntityViewCompiler;
import io.lumnik.hub.search.ProjectionSinkGate;
import io.lumnik.hub.sql.SqlIdentifiers;
import io.lumnik.hub.sql.TemporalText;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static io.lumnik.hub.sql.SqlIdentifiers.quoteIdent;

/**
 * #701 phase 4, trigger row 1 (design doc §4): the ingestion-driven {@code KEY} mark producer for
 * {@code match:} entities. Same seam and catch-and-log posture as {@code EntityResolutionRunHook}
 * (unlike phase 3's tenant-purge listener — this IS best-effort, per design §2: reconciliation is
 * this trigger's safety net, not this hook's own correctness).
 *
 * <p><b>Deliberately does not filter by which table the run touched</b> — {@link ConnectorRunCompleted}
 * carried no such list when this was designed (see design doc §1/§4's "watermark caveat for row 1"; it
 * names them since #904, for the resolution pass). Instead, for every
 * {@code match:} entity of the tenant that has &ge;1 {@code valid} SearchIndex, every source table is
 * scanned unconditionally for {@code _ingested_at > watermark}: a table nobody touched since its
 * watermark simply yields zero rows — correct, never a false mark. This is what makes this hook and
 * {@code SearchProjectionReconciler}'s sweep the *same* predicate, just triggered differently.
 *
 * <p><b>Not cheap until indexed:</b> without {@code (tenant_id, _ingested_at)}, {@code max(_ingested_at)} reads every
 * row the tenant has in the table even when nothing changed — for every indexed table of the tenant on each endpoint
 * run, and for every one in the deployment once a minute in the reconciler. The reconciler creates that index
 * ({@link IngestedAtIndex}, #945), so a table is slow only until its first tick. Without a Search provider
 * nothing would drain those marks, so {@link #onRunCompleted} then does nothing (#896). That is lossless: the
 * watermark only moves in {@link #processTable}, so the first scan once a provider is installed covers every
 * row ingested in between.
 *
 * <p>Runs on the admin pool: cross-tenant discovery of {@code core.search_index} declarations, and
 * reading raw {@code connector.t_*} rows across tenants needs BYPASSRLS the way
 * {@link io.lumnik.hub.search.SearchIndexRevalidator} and {@code ConnectorScheduler} already do — the
 * {@code t_*} tables carry an explicit {@code tenant_id} column filtered by hand in every query here.
 */
@ApplicationScoped
public class SearchProjectionRunHook {

    private static final Logger LOG = Logger.getLogger(SearchProjectionRunHook.class);
    private static final String SCHEMA = "connector";
    private static final String REASON = "ingestion-run";
    private static final String LOOKUP_REASON = "lookup-change";

    // _ingested_at is DEFAULT now() at transaction START, not commit (DynamicTableWriter): a longer
    // transaction that started earlier can commit its rows AFTER a shorter one that started later
    // already advanced the watermark past it, permanently hiding those rows from every future
    // `_ingested_at > watermark` scan. The margin absorbs wall-clock/backend-scheduling jitter around
    // the xact_start boundary this cap is based on.
    private static final Duration COMMIT_ORDER_SAFETY_MARGIN = Duration.ofSeconds(1);

    // markKeys' fetch size and mark-slice size (#895): a backlog (the first scan after a SearchIndex
    // apply reads the table's whole history) is held in heap one slice at a time, one mark INSERT per
    // index. Package-private and mutable, not a {@code static final} constant: a test shrinks it on
    // its OWN, directly-constructed instance (never the shared CDI singleton) to reproduce several
    // slices without needing thousands of rows.
    int matchKeysBatchSize = SearchProjectionMarkStore.MAX_KEYS_PER_INSERT;

    @Inject @DataSource("admin") AgroalDataSource ds;
    @Inject EntityRegistry registry;
    @Inject SearchProjectionMarkStore markStore;
    @Inject SearchProjectionWatermarkStore watermarkStore;
    @Inject ProjectionSinkGate sinkGate;

    private final EntityManifestParser parser = new EntityManifestParser();

    /** {@link #processTenant} itself does not ask the gate. */
    void onRunCompleted(@ObservesAsync ConnectorRunCompleted ev) {
        try {
            if (sinkGate.resolve().isEmpty()) return;
            processTenant(ev.tenantId());
        } catch (Exception e) {
            LOG.warnf(e, "SearchProjectionRunHook: mark production failed for tenant %s run %s — "
                + "reconciliation will pick up any missed rows", ev.tenantId(), ev.runId());
        }
    }

    /**
     * A source table lost rows (#1195: a csv full refresh removed what its file no longer holds). The
     * run path marks the KEY of a row it finds, and a removed row is not there to be found, so every
     * valid SearchIndex over an entity observing the table is marked ALL instead: it rebuilds, and the
     * documents of the rows that left go with it. Best-effort, as the run path is: a failure is logged,
     * and the documents stay until the index's next rebuild. Nothing to do without a Search provider —
     * its first build reads the table as it then stands.
     *
     * @param table the bare {@code t_*} name, as an entity's sources spell it
     */
    public void tableLostRows(long tenantId, String table) {
        try {
            if (sinkGate.resolve().isEmpty()) return;
            for (TableTarget target : targetsByTable(tenantId).getOrDefault(table, List.of())) {
                for (String index : target.indexNames()) {
                    markStore.markAll(tenantId, index, target.entity(), "source-rows-removed");
                }
            }
        } catch (Exception e) {
            LOG.warnf(e, "SearchProjectionRunHook: rows removed from %s.%s for tenant %s could not be marked "
                + "— the indexes over it keep their documents until their next rebuild", SCHEMA, table, tenantId);
        }
    }

    /**
     * A source table, and everything one entity needs to mark rows scanned from it: its own physical
     * match columns (a source's physical&harr;canonical map is the entity's own, not shared across
     * entities even when two entities happen to source the SAME table) and the SearchIndex names to
     * mark for it.
     *
     * <p>Package-private (not {@code private}): {@link SearchProjectionReconciler} reuses this shape
     * via {@link #targetsByTable(long)} rather than re-deriving it from {@code core.search_index}
     * independently — #701 phase 6.
     */
    sealed interface TableTarget permits SourceTarget, LookupTarget, ResolvedLookupTarget {
        String entity();
        List<String> indexNames();
        /** The label its marks carry, for observability. */
        String reason();
    }

    /** The scanned table is a source of {@code entity}: its own match tuples are marked (trigger row 1). */
    record SourceTarget(String entity, List<String> physicalMatchCols, List<String> indexNames,
                        List<String> matchKeyExprs) implements TableTarget {
        @Override public String reason() { return REASON; }
    }

    /**
     * The scanned table is the lookup of one {@code joins[]} entry of {@code entity} (#847). For every
     * lookup key changed since the watermark, the match tuples of the rows of {@code sourceTable} whose
     * {@code physicalFromCol} holds that key are marked. One per (join, source that maps {@code from}).
     */
    record LookupTarget(String entity, List<String> physicalMatchCols, List<String> indexNames,
                        String sourceTable, String physicalFromCol, String lookupToCol,
                        List<String> matchKeyExprs, String fromKey, String toKey) implements TableTarget {
        @Override public String reason() { return LOOKUP_REASON; }
    }

    /**
     * The scanned table is the lookup of one {@code joins[]} entry of the {@code resolution:} entity {@code entity}
     * (#850). For every lookup key changed since the watermark, the resolved entities whose sources carry it —
     * {@code sourceTable} rows whose {@code physicalFromCol} holds the key, through their {@code
     * core.entity_identity} row (the crosswalk of {@code sourceTable}, keyed by {@code physicalIdentityCol}) — are
     * marked by {@code entity_id}. One per (join, source that maps {@code from}).
     */
    record ResolvedLookupTarget(String entity, String entityType, List<String> indexNames, String sourceTable,
                                String physicalFromCol, String physicalIdentityCol, String lookupToCol,
                                String fromKey, String toKey) implements TableTarget {
        @Override public String reason() { return LOOKUP_REASON; }
    }

    /**
     * Every source table one tenant's {@code match:} entities need scanned, and everything needed to
     * mark rows found there — the discovery half of trigger row 1 (design doc §4), reused verbatim by
     * both the event-triggered path below and {@link SearchProjectionReconciler}'s cross-tenant sweep
     * (design doc §5): same predicate, same table discovery, only the trigger differs.
     */
    Map<String, List<TableTarget>> targetsByTable(long tenantId) throws SQLException {
        // Group by physical table FIRST, entity second -- not the other way around. Two match:
        // entities can source the same t_* table; if each ran its own scan-then-advance transaction
        // independently, the second one to commit would read a watermark the first one already moved
        // past, silently losing its own marks for the rows the first entity's scan already consumed
        // (the table's watermark carries no memory of "consumed for which entity" -- it is one row per
        // (tenant, table), by design). One scan per table, shared by every entity sourcing it, avoids
        // the race entirely: the watermark advances only once ALL of that table's entities have been
        // marked from the exact same scanned range.
        Map<String, List<String>> indexNamesByEntity;
        try (Connection c = ds.getConnection()) {
            indexNamesByEntity = markStore.validIndexNamesByEntity(c, tenantId);
        }

        Map<String, List<TableTarget>> targetsByTable = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : indexNamesByEntity.entrySet()) {
            String entityName = e.getKey();
            List<String> indexNames = e.getValue();

            Optional<EntityManifest> stored = storedManifest(entityName);
            if (stored.isEmpty()) continue; // no longer stored (a resolution entity's own sources are CascadeResolver's job, row 1')

            EntityManifest m = stored.get();
            Observed observed = observed(m);
            // A mark says a key as the view groups it: a date beside a timestamp is one value (#1325), and it meets a
            // lookup key as the view joins it: a day-keyed lookup at the UTC day (#1329).
            boolean scans = !observed.sources().isEmpty() || !observed.resolvedLookups().isEmpty();   // lookups come with sources
            EntityViewCompiler.Sources sources = scans ? catalog(m) : null;
            for (ScannedSource source : observed.sources()) {
                targetsByTable.computeIfAbsent(source.table(), k -> new ArrayList<>())
                    .add(new SourceTarget(m.name(), source.physicalMatchCols(), indexNames,
                        EntityViewCompiler.matchKeyExpressions(m, sources, source.table())));
            }
            for (LookupScan l : observed.lookups()) {
                targetsByTable.computeIfAbsent(l.lookupTable(), k -> new ArrayList<>())
                    .add(new LookupTarget(m.name(), l.physicalMatchCols(), indexNames, l.sourceTable(),
                        l.physicalFromCol(), l.lookupToCol(),
                        EntityViewCompiler.matchKeyExpressions(m, sources, l.sourceTable()),
                        EntityViewCompiler.lookupFromKey(m, sources, l.join(), l.sourceTable(), l.physicalFromCol(), ""),
                        EntityViewCompiler.lookupToKey(m, sources, l.join())));
            }
            for (ResolvedLookupScan l : observed.resolvedLookups()) {
                targetsByTable.computeIfAbsent(l.lookupTable(), k -> new ArrayList<>())
                    .add(new ResolvedLookupTarget(m.name(), m.name().toLowerCase(Locale.ROOT), indexNames, l.sourceTable(),
                        l.physicalFromCol(), l.physicalIdentityCol(), l.lookupToCol(),
                        EntityViewCompiler.lookupFromKey(m, sources, l.join(), l.sourceTable(), l.physicalFromCol(), "s."),
                        EntityViewCompiler.lookupToKey(m, sources, l.join())));
            }
        }
        return targetsByTable;
    }

    /**
     * Discovers, then scans-marks-advances every table for one tenant. The event-triggered path;
     * {@link SearchProjectionReconciler} calls {@link #targetsByTable(long)} and
     * {@link #processTable(long, String, List)} directly instead of this method, so that one bad
     * table's failure does not abort every other table in the same sweep tick (this method, by
     * contrast, lets a single table's exception propagate to {@link #onRunCompleted}'s catch-and-log,
     * which is scoped to the whole event — the existing, unchanged phase-4 posture).
     */
    void processTenant(long tenantId) throws SQLException {
        for (Map.Entry<String, List<TableTarget>> e : targetsByTable(tenantId).entrySet()) {
            processTable(tenantId, e.getKey(), e.getValue());
        }
    }

    /**
     * One (tenant, table) pair, its own transaction, shared by every entity that sources it (design
     * doc's scan-then-mark-then-advance, widened from "one entity" to "every entity on this table" to
     * close the race described on {@link #process}): a crash between marking and advancing the
     * watermark re-scans (safe, idempotent) rather than advancing without having actually marked
     * (unsafe — would silently skip rows for whichever entity had not yet been marked).
     *
     * <p><b>The bound is read fresh, per table, right before this table's own scan</b> ({@link
     * #safeBoundAdmin()}, #730): a snapshot taken earlier cannot see a transaction that opened after it,
     * so a younger transaction's committed row could drag the watermark past an older, still-open one
     * and hide that transaction's rows from every future {@code _ingested_at > watermark} scan.
     */
    void processTable(long tenantId, String tableName, List<TableTarget> targets) throws SQLException {
        // Two different spellings on purpose: `table` is what goes in the SQL FROM clause (quoted,
        // escaped); `qualifiedTableName` is the bookkeeping key stored in
        // core.search_projection_mark_watermark.table_name, which V56 documents as "schema-qualified
        // connector.t_* name" -- kept exactly that shape so a later reconciler sweep (design doc §5)
        // that discovers tables independently of this hook agrees on the same key.
        String table = SCHEMA + "." + quoteIdent(tableName);
        String qualifiedTableName = SCHEMA + "." + tableName;

        // Fresh, and BEFORE the scan (and before this method takes its own pooled connection): any transaction
        // that started at or before `bound` had already committed when it was read, so the scan below sees its rows.
        Instant bound = safeBoundAdmin();
        try (Connection c = ds.getConnection()) {
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                // A key's text in UTC, as the projection reads the view's _canonical_key (#1325).
                TemporalText.utcSession(c);
                Optional<Instant> watermark = watermarkStore.watermark(c, tenantId, qualifiedTableName);
                Instant maxIngested = maxIngestedAt(c, table, tenantId, watermark);
                if (maxIngested != null) {
                    // Every entity sourcing this table is marked from the SAME scanned range before the
                    // watermark moves — the fix for the shared-table race: none of them can see the
                    // watermark already advanced past rows they had not yet been marked for.
                    // Every distinct key in range is marked when this loop ends, uncommitted until the advance below.
                    for (TableTarget target : targets) {
                        // #847: a lookup's targets mark through its sources, in the same transaction as the advance below.
                        switch (target) {
                            case SourceTarget s -> markMatchKeys(c, s, table, tenantId, watermark);
                            case LookupTarget l -> markLookupKeys(c, l, table, tenantId, watermark);
                            case ResolvedLookupTarget r -> markResolvedLookupKeys(c, r, table, tenantId, watermark);
                        }
                    }
                    // Advance to the max _ingested_at actually observed, never to now(): advancing to
                    // now() risks missing a row committed between this scan and now() under a
                    // concurrent write to the SAME table from a DIFFERENT run. Design doc §3. Also never
                    // past `bound`: a still-open transaction older than `bound` may yet commit rows
                    // stamped earlier than maxIngested — advancing past it would hide them forever.
                    Instant target = maxIngested.isBefore(bound) ? maxIngested : bound;
                    watermarkStore.advance(c, tenantId, qualifiedTableName, target);
                } else {
                    // #730: nothing after the watermark. Without this an idle table's watermark froze at
                    // its last row and froze every asOf built on it. Monotone (advance() never goes
                    // backwards).
                    watermarkStore.advance(c, tenantId, qualifiedTableName, bound);
                }
                c.commit();
            } catch (SQLException | RuntimeException ex) {
                c.rollback();
                throw ex;
            } finally {
                c.setAutoCommit(autoCommit);
            }
        }
    }

    /**
     * Pure: the source's physical column for each of the entity's declared {@code match} columns, in
     * match order — the same physical&harr;canonical mapping convention
     * {@code EntityViewCompiler#validatedCanonToSrc}/{@code matchKey} use, mirrored here
     * rather than reinvented. {@code null} when this source does not map every match column (should
     * never happen for a manifest {@code EntityManifestParser} already validated, but a defensive skip
     * beats an NPE in a catch-and-log hook).
     */
    static List<String> physicalMatchColumns(EntityManifest m, EntityManifest.Source source) {
        if (source == null || source.map() == null) return null;
        Map<String, String> canonToSrc = new HashMap<>();
        for (Map.Entry<String, String> e : source.map().entrySet()) canonToSrc.put(e.getValue(), e.getKey());

        List<String> physical = new ArrayList<>(m.match().size());
        for (String matchCol : m.match()) {
            String p = canonToSrc.get(matchCol);
            if (p == null) return null;
            physical.add(p);
        }
        return physical;
    }

    /**
     * The instant strictly behind which every hub change is already committed (#730) — one statement,
     * on the database's own clock (the same clock that stamps {@code _ingested_at}), so a JVM clock
     * skew cannot make it too new: {@code least(clock_timestamp(), oldest OTHER open transaction's
     * xact_start) − margin}. With nothing else open the oldest-start term falls back to {@code
     * clock_timestamp()}, so there is always an upper clamp (the old cap answered {@code Instant.MAX}
     * there). The margin applies to BOTH terms: a transaction that starts between the {@code
     * pg_stat_activity} snapshot and {@code clock_timestamp()} is invisible to the snapshot yet has an
     * {@code xact_start} at or before the clock reading — the margin keeps it behind the bound. Any
     * transaction that started at or before the returned instant has committed by the time it is read.
     *
     * <p>Scoped to {@code current_database()} ({@code pg_stat_activity} is cluster-wide). {@code
     * xact_start} of another role's session is only visible to a role that may read it: it works here
     * because every {@code connector.t_*} writer runs as the same {@code lumnik_admin} role as this
     * query — a future writer on another role would be invisible to this bound.
     */
    public Instant safeBoundAdmin() {
        return Jdbc.queryOne(ds, "SearchProjectionRunHook.safeBoundAdmin",
                "SELECT least(clock_timestamp(), coalesce(min(xact_start), clock_timestamp())) "
              + "- make_interval(secs => ?) "
              + "FROM pg_stat_activity WHERE xact_start IS NOT NULL "
              + "AND pid <> pg_backend_pid() AND datname = current_database()",
                ps -> ps.setDouble(1, COMMIT_ORDER_SAFETY_MARGIN.toMillis() / 1000.0),
                rs -> Jdbc.instant(rs, 1))
            .orElseThrow(() -> new IllegalStateException("safeBound query returned no row"));
    }

    /** What the catalog says of {@code m}'s tables: the types its match key is aligned by (#1325). */
    private EntityViewCompiler.Sources catalog(EntityManifest m) throws SQLException {
        try (Connection c = ds.getConnection()) {
            return EntityViewCompiler.sources(c, m);
        }
    }

    /** One scanned source of a {@code match:} entity: its table and the physical columns its match key is read from. */
    private record ScannedSource(String table, List<String> physicalMatchCols) {}

    /**
     * The stored {@code match:} manifest for {@code entityName}; empty when it is no longer stored or
     * is a {@code resolution:} entity. Its tables are read through {@link #observed} only (see there).
     */
    private Optional<EntityManifest> matchManifest(String entityName) {
        return storedManifest(entityName).filter(m -> m.resolution() == null);
    }

    /** The stored manifest of {@code entityName}, whichever its mode; empty when it is no longer stored. */
    private Optional<EntityManifest> storedManifest(String entityName) {
        return registry.stored(entityName).map(stored -> parser.parse(stored.yaml()));
    }

    /** What an entity observes: its direct sources, and per join the sources able to reach its lookup. */
    private record Observed(List<ScannedSource> sources, List<LookupScan> lookups,
                            List<ResolvedLookupScan> resolvedLookups) {
        /** The tables a {@code match:} entity's asOf bound waits for: sources and lookups. Empty for a resolution entity. */
        List<String> tables() {
            Set<String> all = new LinkedHashSet<>();
            for (ScannedSource s : sources) all.add(s.table());
            for (LookupScan l : lookups) all.add(l.lookupTable());
            return List.copyOf(all);
        }

        /** The lookup tables a resolution entity's asOf bound waits for (#850); empty for a {@code match:} entity. */
        List<String> resolvedLookupTables() {
            return resolvedLookups.stream().map(ResolvedLookupScan::lookupTable).distinct().toList();
        }
    }

    private record LookupScan(String lookupTable, String sourceTable, String physicalFromCol,
                              List<String> physicalMatchCols, String lookupToCol, EntityManifest.Join join) {}

    private record ResolvedLookupScan(String lookupTable, String sourceTable, String physicalFromCol,
                                      String physicalIdentityCol, String lookupToCol, EntityManifest.Join join) {}

    /** The ONE rule for "which physical tables does an entity observe": marking and the asOf bound both read it. */
    private static Observed observed(EntityManifest m) {
        if (m.resolution() != null) return observedResolved(m);
        List<ScannedSource> sources = new ArrayList<>();
        List<LookupScan> lookups = new ArrayList<>();
        for (EntityManifest.Source source : m.sources()) {
            List<String> matchCols = physicalMatchColumns(m, source);
            if (matchCols == null) continue; // source doesn't map every match column -- nothing to scan
            sources.add(new ScannedSource(source.table(), matchCols));
            for (EntityManifest.Join join : m.joins()) {
                String fromCol = physicalColumnFor(source, join.from());
                if (fromCol != null) {
                    lookups.add(new LookupScan(join.table(), source.table(), fromCol, matchCols, join.to(), join));
                }
            }
        }
        return new Observed(sources, lookups, List.of());
    }

    /** A resolution entity scans no source (run coverage bounds them, #748); only its lookups are observed (#850). */
    private static Observed observedResolved(EntityManifest m) {
        List<ResolvedLookupScan> resolved = new ArrayList<>();
        for (EntityManifest.Source source : m.sources()) {
            // A stored manifest was validated at apply (identity covers exactly the declared sources): a source without
            // an identity column is a broken invariant, and it fails loudly rather than being skipped.
            String identityCol = m.resolution().physicalIdentityColumn(source);
            for (EntityManifest.Join join : m.joins()) {
                String fromCol = physicalColumnFor(source, join.from());
                if (fromCol != null) {
                    resolved.add(new ResolvedLookupScan(join.table(), source.table(), fromCol, identityCol, join.to(), join));
                }
            }
        }
        return new Observed(List.of(), List.of(), resolved);
    }

    /** The physical column of {@code source} mapped to {@code canonical} (the last one, as {@link #physicalMatchColumns}), or null. */
    private static String physicalColumnFor(EntityManifest.Source source, String canonical) {
        String physical = null;
        if (source.map() != null) {
            for (Map.Entry<String, String> e : source.map().entrySet()) {
                if (e.getValue().equals(canonical)) physical = e.getKey();
            }
        }
        return physical;
    }

    /**
     * #730: the schema-qualified source tables this hook scans for a {@code match:} entity — the
     * watermark keys ({@code core.search_projection_mark_watermark.table_name}) that bound its
     * freshness. Empty for a {@code resolution:} entity (its marks are decision-driven and its
     * canonical view LEFT JOINs undecided rows, so no source watermark bounds it — its bound is run
     * coverage, see {@code SearchProjectionAsOfAdvancer}, #748), an unknown one, or one with no scanned
     * source.
     */
    public List<String> matchSourceTables(String entityName) {
        return matchManifest(entityName).stream()
            .flatMap(m -> observed(m).tables().stream())
            .map(table -> SCHEMA + "." + table)
            .toList();
    }

    /**
     * #850: the schema-qualified lookup tables of a {@code resolution:} entity's joins — the tables its asOf bound waits
     * for besides run coverage. Derived from the same {@link #observed} as the marking. Empty for a {@code match:}
     * entity (those tables are in {@link #matchSourceTables}), an unknown one, or one without joins.
     */
    public List<String> resolutionLookupTables(String entityName) {
        return storedManifest(entityName).filter(m -> m.resolution() != null).stream()
            .flatMap(m -> observed(m).resolvedLookupTables().stream())
            .map(table -> SCHEMA + "." + table)
            .toList();
    }

    /** #748: true when the stored manifest of {@code entityName} declares {@code resolution:}. */
    public boolean isResolutionEntity(String entityName) {
        return registry.stored(entityName)
            .map(stored -> parser.parse(stored.yaml()))
            .filter(m -> m.resolution() != null)
            .isPresent();
    }

    private Instant maxIngestedAt(Connection c, String table, long tenantId, Optional<Instant> watermark)
            throws SQLException {
        String sql = "SELECT max(_ingested_at) FROM " + table + " WHERE tenant_id = ?"
            + (watermark.isPresent() ? " AND _ingested_at > ?" : "");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            if (watermark.isPresent()) ps.setTimestamp(2, Timestamp.from(watermark.get()));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return Jdbc.instant(rs, 1);
            }
        }
    }

    private void markMatchKeys(Connection c, SourceTarget target, String table, long tenantId,
                               Optional<Instant> watermark) throws SQLException {
        String fromWhere = " FROM " + table + " WHERE tenant_id = ?" + watermarkPredicate(watermark);
        markKeys(c, tenantId, target, target.matchKeyExprs(), fromWhere, leadingBinds(watermark, tenantId),
            MatchKeyEncoding::encode);
    }

    /**
     * #847: marks the encoded match keys of every row of the lookup's source whose {@code from} value is a lookup
     * key changed since the watermark ({@code _ingested_at > watermark}, absent = -infinity). The changed
     * keys are never materialised: they stay a sub-select, so a first load of a big lookup costs one
     * semi-join, not a key list. Marking a superset is safe (marks are idempotent); missing a
     * key is not.
     */
    private void markLookupKeys(Connection c, LookupTarget target, String lookupTable, long tenantId,
                                Optional<Instant> watermark) throws SQLException {
        String fromWhere = " FROM " + SCHEMA + "." + quoteIdent(target.sourceTable())
            + " WHERE tenant_id = ? AND " + target.fromKey() + " IN (SELECT " + target.toKey() + " FROM " + lookupTable
            + " WHERE tenant_id = ?" + watermarkPredicate(watermark) + ")";
        markKeys(c, tenantId, target, target.matchKeyExprs(), fromWhere,
            leadingBinds(watermark, tenantId, tenantId), MatchKeyEncoding::encode);
    }

    /**
     * #850: marks the {@code entity_id} of every resolved identity of the lookup's source whose {@code from} value is
     * a lookup key changed since the watermark. Admin pool: the SQL is the only isolation — the tenant is bound on the
     * source, {@code core.entity_identity} is correlated to it, the lookup sub-select binds it again. The changed
     * keys stay a sub-select (never materialised); {@code DISTINCT} marks an identity once when several rows reach it.
     */
    private void markResolvedLookupKeys(Connection c, ResolvedLookupTarget target, String lookupTable,
                                        long tenantId, Optional<Instant> watermark) throws SQLException {
        String fromWhere = " FROM " + SCHEMA + "." + quoteIdent(target.sourceTable()) + " s"
            + " JOIN core.entity_identity ei ON ei.tenant_id = s.tenant_id AND ei.entity_type = ? AND ei.source = ?"
            + " AND ei.source_id = s." + quoteIdent(target.physicalIdentityCol()) + "::text AND ei.state = 'resolved'"
            + " WHERE s.tenant_id = ? AND " + target.fromKey() + " IN (SELECT " + target.toKey() + " FROM " + lookupTable
            + " WHERE tenant_id = ?" + watermarkPredicate(watermark) + ")";
        List<Object> binds = new ArrayList<>(List.of(target.entityType(), target.sourceTable(), tenantId, tenantId));
        watermark.ifPresent(w -> binds.add(Timestamp.from(w)));
        markKeys(c, tenantId, target, List.of("ei.entity_id"), fromWhere, binds, row -> row.get(0));
    }

    private static String watermarkPredicate(Optional<Instant> watermark) {
        return watermark.isPresent() ? " AND _ingested_at > ?" : "";
    }

    /** The binds of {@code fromWhere}'s own placeholders, in order: each tenant, then the watermark if any. */
    private static List<Object> leadingBinds(Optional<Instant> watermark, long... tenants) {
        List<Object> binds = new ArrayList<>();
        for (long t : tenants) binds.add(t);
        watermark.ifPresent(w -> binds.add(Timestamp.from(w)));
        return binds;
    }

    /**
     * Marks every distinct key tuple of {@code fromWhere}, encoded, for each index of {@code target}, one slice at a
     * time while the cursor stays open (#895). The fetch size streams only because {@link #processTable}'s connection
     * is out of autocommit; otherwise the driver would buffer the whole result. The {@code ORDER BY} writes a target's
     * marks in one order, so two scans of the same table racing on the same keys (a run event and a reconciler tick)
     * queue behind each other instead of deadlocking. Every column is cast to {@code ::text}, matching {@code
     * EntityViewCompiler}'s own {@code _canonical_key} cast (code review).
     */
    private void markKeys(Connection c, long tenantId, TableTarget target, List<String> keyExprs, String fromWhere,
                          List<Object> binds, Function<List<String>, String> encode) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT DISTINCT ");
        appendCastedCols(sql, keyExprs);
        sql.append(fromWhere);
        for (String expr : keyExprs) sql.append(" AND ").append(expr).append(" IS NOT NULL");
        sql.append(" ORDER BY ");
        appendCastedCols(sql, keyExprs);

        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            ps.setFetchSize(matchKeysBatchSize);
            int idx = 1;
            for (Object bind : binds) ps.setObject(idx++, bind);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> slice = new ArrayList<>(matchKeysBatchSize);
                while (rs.next()) {
                    List<String> values = new ArrayList<>(keyExprs.size());
                    for (int i = 1; i <= keyExprs.size(); i++) values.add(rs.getString(i));
                    slice.add(encode.apply(values));
                    if (slice.size() == matchKeysBatchSize) {
                        markSlice(c, tenantId, target, slice);
                        slice = new ArrayList<>(matchKeysBatchSize);
                    }
                }
                if (!slice.isEmpty()) markSlice(c, tenantId, target, slice);
            }
        }
    }

    private void markSlice(Connection c, long tenantId, TableTarget target, List<String> slice) throws SQLException {
        for (String indexName : target.indexNames()) {
            markStore.markKeysAsAdmin(c, tenantId, indexName, target.entity(), slice, target.reason());
        }
    }

    /** {@code keyExprs} are SQL expressions already quoted/qualified by the caller (a quoted column, or {@code ei.entity_id}). */
    private static void appendCastedCols(StringBuilder sql, List<String> keyExprs) {
        for (int i = 0; i < keyExprs.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(keyExprs.get(i)).append("::text");
        }
    }
}
