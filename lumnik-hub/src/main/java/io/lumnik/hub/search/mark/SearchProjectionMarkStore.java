package io.lumnik.hub.search.mark;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes {@code core.search_projection_mark} rows (V56, design §3/§6): cross-tenant, deployment-wide
 * bookkeeping like {@link io.lumnik.hub.search.SearchIndexRevalidator}, not request-scoped like
 * {@link io.lumnik.hub.search.SearchIndexStore} — so its OWN {@code ds} field is the {@code admin}
 * pool, never the RLS-enforced {@code default} one.
 *
 * <p>Every write is {@code INSERT ... ON CONFLICT (tenant_id, search_index, mark_kind, canonical_key)
 * DO UPDATE}: idempotent by construction (design §2), so re-delivering the same trigger produces no
 * duplicate logical work — a concurrent re-mark of an undrained row reissues it with a fresh id rather
 * than silently no-op'ing (design doc §1).
 *
 * <p>Each mark method has two overloads. The {@link Connection} overload runs on WHATEVER connection
 * the caller already holds open in its own transaction — that can be this class's own {@code admin}
 * pool (e.g. {@code SearchProjectionRunHook}'s scan-then-mark transaction), but just as often the
 * caller's REQUEST-scoped, RLS-enforced {@code default} connection instead: {@code
 * SearchIndexStore.upsert}'s {@code doReturningWork} and {@code CascadeResolver.resolve} both call
 * these overloads on their own default-datasource transaction, so the mark commits or rolls back
 * atomically with the write that produced it, on whatever datasource that write already lives on —
 * the same reason {@link io.lumnik.hub.entity.EntityDefinitionLock#shared(Connection, String)} takes
 * an explicit connection instead of opening its own. The no-argument overload is genuinely admin-only:
 * it opens this class's own {@code ds} connection for a caller with no transaction of its own to join.
 */
@ApplicationScoped
public class SearchProjectionMarkStore {

    // V57's GRANT UPDATE (id, marked_at) ON core.search_projection_mark TO lumnik_app is
    // deliberately narrow — entity/reason stay append-only for that role (V57's own comment).
    // mark(Connection c, ...) below may run on a CALLER's own connection, which is sometimes
    // exactly that lumnik_app-scoped one (SearchIndexStore.upsert) — see
    // SearchProjectionMarkReMarkRlsIT, which proves a permission error there under real,
    // non-superuser privileges. INSERT_SQL must stay narrow for that reason.
    private static final String INSERT_SQL =
        "INSERT INTO core.search_projection_mark (tenant_id, search_index, entity, mark_kind, canonical_key, reason) "
      + "VALUES (?, ?, ?, ?, ?, ?) "
      + "ON CONFLICT (tenant_id, search_index, mark_kind, canonical_key) DO UPDATE SET "
      + "id = nextval(pg_get_serial_sequence('core.search_projection_mark', 'id')), marked_at = now()";

    // #719: the no-Connection mark(long, ...) overload below always runs on THIS class's own
    // admin pool (never a caller-supplied connection — see its own javadoc), which V57's grant
    // never restricts. A caller filtering marks by `reason` (e.g. the entity-reapply acceptance
    // check) needs a re-mark's OWN trigger name to actually land, not whichever trigger wrote the
    // row first — safe to widen only here, where the admin role's full privilege is guaranteed.
    private static final String ADMIN_INSERT_SQL =
        INSERT_SQL + ", entity = EXCLUDED.entity, reason = EXCLUDED.reason";

    /** Keys per {@code markKeys} INSERT: chunked so a large scan (ingestion, reconciliation) never
     *  risks PostgreSQL's 65535-bind-parameter limit (6 params/key -&gt; ~10,922 keys) or building one
     *  unreasonably large SQL string — comfortably under either, still one round trip per few
     *  thousand keys rather than one per key. Package-private so {@code
     *  SearchProjectionMarkStoreBatchingTest} pins the real boundary, not a duplicated literal. */
    static final int MAX_KEYS_PER_INSERT = 1000;

    @Inject @DataSource("admin") AgroalDataSource ds;

    // -- markKeys ------------------------------------------------------------------------------

    /** Opens its own admin connection for a caller with no transaction of its own to join — see the
     *  {@link Connection} overload for the atomic-with-the-caller's-own-write case. */
    public void markKeys(long tenantId, String searchIndex, String entity, List<String> canonicalKeys, String reason) {
        if (canonicalKeys == null)
            throw new IllegalArgumentException("canonicalKeys must not be null");
        if (canonicalKeys.isEmpty()) return;
        try (Connection c = ds.getConnection()) {
            markKeys(c, tenantId, searchIndex, entity, canonicalKeys, reason);
        } catch (SQLException e) {
            throw new RuntimeException("SearchProjectionMarkStore.markKeys: " + e.getMessage(), e);
        }
    }

    /** One batched multi-row INSERT of {@code KEY} marks per {@link #MAX_KEYS_PER_INSERT}-sized
     *  chunk of {@code canonicalKeys} — never one unbounded statement for however many keys a scan
     *  found. Each chunk is idempotent on its own ({@code ON CONFLICT DO UPDATE} reissues an
     *  already-pending row rather than duplicating it), so a caller whose connection dies partway
     *  through a large batch loses nothing already written and can safely retry the whole call. */
    public void markKeys(Connection c, long tenantId, String searchIndex, String entity,
                          List<String> canonicalKeys, String reason) throws SQLException {
        insertKeys(c, tenantId, searchIndex, entity, canonicalKeys, reason, false);
    }

    /** {@link #markKeys(Connection, long, String, String, List, String)} for a caller on the ADMIN pool only
     *  (the projection run hook and, through it, the reconciler): a re-mark of an already-pending key also
     *  replaces {@code entity} and {@code reason}, so the label names the latest trigger (#852) — the same
     *  widening {@code markAll}'s admin path got in #719. Never call it on a request-scoped {@code lumnik_app}
     *  connection: V57 grants that role {@code UPDATE (id, marked_at)} only, and the statement would fail. */
    public void markKeysAsAdmin(Connection c, long tenantId, String searchIndex, String entity,
                                List<String> canonicalKeys, String reason) throws SQLException {
        insertKeys(c, tenantId, searchIndex, entity, canonicalKeys, reason, true);
    }

    private void insertKeys(Connection c, long tenantId, String searchIndex, String entity,
                            List<String> canonicalKeys, String reason, boolean relabel) throws SQLException {
        if (searchIndex == null || searchIndex.isBlank())
            throw new IllegalArgumentException("searchIndex must not be blank");
        if (entity == null || entity.isBlank())
            throw new IllegalArgumentException("entity must not be blank");
        if (reason == null || reason.isBlank())
            throw new IllegalArgumentException("reason must not be blank");
        if (canonicalKeys == null)
            throw new IllegalArgumentException("canonicalKeys must not be null");
        for (int i = 0; i < canonicalKeys.size(); i++) {
            String key = canonicalKeys.get(i);
            if (key == null || key.isBlank())
                // Never interpolate the value itself (Copilot review): a canonical key encodes a
                // business value, so only its position in the batch is safe to name in a message
                // that can reach logs or an error surface.
                throw new IllegalArgumentException("KEY marks require a non-blank canonicalKey at index " + i);
        }
        // Deduplicated AFTER validation, on the caller's own values (Copilot review): a duplicate key
        // within the SAME multi-row INSERT's VALUES list would have Postgres try to DO UPDATE the same
        // conflicting row twice in one statement, which it refuses outright ("ON CONFLICT DO UPDATE
        // command cannot affect row a second time") -- a hard failure, not a soft one, so this method
        // must never rely on every caller already happening to pass an already-duplicate-free list.
        List<String> distinctKeys = new ArrayList<>(new LinkedHashSet<>(canonicalKeys));
        for (int offset = 0; offset < distinctKeys.size(); offset += MAX_KEYS_PER_INSERT) {
            List<String> chunk = distinctKeys.subList(offset, Math.min(offset + MAX_KEYS_PER_INSERT, distinctKeys.size()));
            try (PreparedStatement ps = c.prepareStatement(keysInsertSql(chunk.size(), relabel))) {
                bindKeys(ps, tenantId, searchIndex, entity, chunk, reason);
                ps.executeUpdate();
            }
        }
    }

    private static String keysInsertSql(int keyCount, boolean relabel) {
        StringBuilder sql = new StringBuilder(
            "INSERT INTO core.search_projection_mark (tenant_id, search_index, entity, mark_kind, canonical_key, reason) VALUES ");
        for (int i = 0; i < keyCount; i++) {
            if (i > 0) sql.append(", ");
            sql.append("(?, ?, ?, ?, ?, ?)");
        }
        // Narrow unless `relabel` (admin-pool callers, #852), same reasoning as INSERT_SQL above:
        // markKeys(Connection c, ...) may run on a caller's own connection, and V57 grants
        // lumnik_app UPDATE on (id, marked_at) only.
        sql.append(" ON CONFLICT (tenant_id, search_index, mark_kind, canonical_key) DO UPDATE SET "
            + "id = nextval(pg_get_serial_sequence('core.search_projection_mark', 'id')), marked_at = now()"
            + (relabel ? ", entity = EXCLUDED.entity, reason = EXCLUDED.reason" : ""));
        return sql.toString();
    }

    private static void bindKeys(PreparedStatement ps, long tenantId, String searchIndex, String entity,
                                 List<String> canonicalKeys, String reason) throws SQLException {
        int i = 1;
        for (String key : canonicalKeys) {
            ps.setLong(i++, tenantId);
            ps.setString(i++, searchIndex);
            ps.setString(i++, entity);
            ps.setString(i++, MarkKind.KEY.sqlValue());
            ps.setString(i++, key);
            ps.setString(i++, reason);
        }
    }

    // -- markAll --

    public void markAll(long tenantId, String searchIndex, String entity, String reason) {
        mark(tenantId, searchIndex, entity, MarkKind.ALL, reason);
    }

    public void markAll(Connection c, long tenantId, String searchIndex, String entity, String reason)
            throws SQLException {
        mark(c, tenantId, searchIndex, entity, MarkKind.ALL, reason);
    }

    private void mark(long tenantId, String searchIndex, String entity, MarkKind kind, String reason) {
        Jdbc.update(ds, "SearchProjectionMarkStore.mark" + kind.sqlValue(), ADMIN_INSERT_SQL,
            ps -> bindOne(ps, tenantId, searchIndex, entity, kind, reason));
    }

    private void mark(Connection c, long tenantId, String searchIndex, String entity, MarkKind kind, String reason)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(INSERT_SQL)) {
            bindOne(ps, tenantId, searchIndex, entity, kind, reason);
            ps.executeUpdate();
        }
    }

    private static void bindOne(PreparedStatement ps, long tenantId, String searchIndex, String entity,
                                 MarkKind kind, String reason) throws SQLException {
        ps.setLong(1, tenantId);
        ps.setString(2, searchIndex);
        ps.setString(3, entity);
        ps.setString(4, kind.sqlValue());
        ps.setString(5, "");
        ps.setString(6, reason);
    }

    // -- reads -----------------------------------------------------------------------------------

    /**
     * Every tenant's currently-valid SearchIndex names, grouped by the entity they declare over
     * (#701 phase 4/5, design §4 trigger rows 1 and 3). Used by {@code SearchProjectionRunHook}'s
     * own scan-then-mark loop, which genuinely needs every entity of the tenant at once (it fans
     * out over all of them per {@code ConnectorRunCompleted}). {@code CascadeResolver} wants only
     * ONE entity's names per call — see {@link #validIndexNamesForEntity} instead, scoped in SQL
     * rather than filtering this whole-tenant map on a hot per-row path.
     *
     * <p>The key is {@code lower(entity)}, not {@code core.search_index.entity} verbatim — see
     * {@link #validIndexNamesForEntity}'s javadoc for why.
     */
    public Map<String, List<String>> validIndexNamesByEntity(Connection c, long tenantId) throws SQLException {
        Map<String, List<String>> byEntity = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT lower(entity), name FROM core.search_index WHERE tenant_id = ? AND status = 'valid'")) {
            ps.setLong(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    byEntity.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
                }
            }
        }
        return byEntity;
    }

    /**
     * Every tenant's currently-valid SearchIndex names over exactly ONE entity — {@code
     * CascadeResolver.markResolutionKeys}'s lookup, scoped in SQL rather than loading every entity's
     * declarations (as {@link #validIndexNamesByEntity} does) only to throw away everything but one
     * {@code Map.get(entityType)}: this runs once per resolved source row, potentially every row of
     * a bulk incremental pass, so the whole-tenant scan cost is not free to repeat per row.
     *
     * <p>{@code lower(entity) = lower(?)}, not a verbatim comparison: {@code entityType} is always
     * {@code EntityManifest.name().toLowerCase(Locale.ROOT)} (e.g. {@code CascadeResolver},
     * {@code EntityAssertionResource}), and {@code core.search_index.entity} may be stored in
     * whatever case the applying manifest used — a verbatim compare would silently miss a
     * differently-cased declaration. Mirrors the same {@code lower(entity)} comparison
     * {@code SearchIndexStore} and {@code SearchIndexRevalidator} already use in SQL.
     */
    public List<String> validIndexNamesForEntity(Connection c, long tenantId, String entityType) throws SQLException {
        List<String> names = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT name FROM core.search_index "
              + "WHERE tenant_id = ? AND status = 'valid' AND lower(entity) = lower(?)")) {
            ps.setLong(1, tenantId);
            ps.setString(2, entityType);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) names.add(rs.getString(1));
            }
        }
        return names;
    }

    /**
     * Every tenant that currently owns &ge;1 {@code valid} SearchIndex — {@link SearchProjectionReconciler}'s
     * (#701 phase 6) cross-tenant fan-out point: the sweep then reuses {@link SearchProjectionRunHook}'s
     * own per-tenant discovery ({@code targetsByTable}) for each id returned here, so table discovery
     * itself starts from {@code core.search_index} exactly once, not duplicated between this store and
     * the hook.
     */
    public List<Long> tenantsWithValidIndex(Connection c) throws SQLException {
        List<Long> tenantIds = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT DISTINCT tenant_id FROM core.search_index WHERE status = 'valid'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) tenantIds.add(rs.getLong(1));
        }
        return tenantIds;
    }

    /**
     * Every tenant's valid SearchIndex names grouped by entity, for {@link
     * io.lumnik.hub.search.projection.SearchProjectionWorker}'s discovery tick — one admin query for
     * the whole deployment rather than one to list tenants plus one more per tenant returned: at a few
     * hundred tenants with a valid index, the per-tenant shape meant hundreds of extra connection
     * acquisitions every single minute purely for dispatch bookkeeping.
     */
    private record ValidIndexRow(long tenantId, String entity, String name) {}

    public Map<Long, Map<String, List<String>>> validIndexNamesByEntityAndTenantAdmin() {
        List<ValidIndexRow> rows = Jdbc.query(ds, "SearchProjectionMarkStore.validIndexNamesByEntityAndTenantAdmin",
            "SELECT tenant_id, lower(entity), name FROM core.search_index WHERE status = 'valid' ORDER BY tenant_id",
            ps -> {},
            rs -> new ValidIndexRow(rs.getLong(1), rs.getString(2), rs.getString(3)));

        Map<Long, Map<String, List<String>>> byTenant = new LinkedHashMap<>();
        for (ValidIndexRow row : rows) {
            byTenant.computeIfAbsent(row.tenantId(), k -> new LinkedHashMap<>())
                .computeIfAbsent(row.entity(), k -> new ArrayList<>())
                .add(row.name());
        }
        return byTenant;
    }

    /**
     * The mark table's own head id for (tenantId, searchIndex) at this instant — {@code
     * SearchProjectionWorker.rebuild}'s own watermark read (design doc §5b step 1), taken before the
     * declaration re-read so a concurrent re-apply's own mark is either fully included here or not
     * counted at all, never split. COALESCE'd to 0 when the table is empty for this pair — never
     * NULL fighting a state row's own NOT NULL position column. Not used for the catch-up dispatch
     * decision — see {@link #pendingMarkPairsAdmin()} for that (#712: a state row's {@code position}
     * is never compared against this value to decide catch-up vs. "nothing this tick").
     */
    public long maxMarkId(long tenantId, String searchIndex) {
        return Jdbc.queryOne(ds, "SearchProjectionMarkStore.maxMarkId",
                "SELECT COALESCE(max(id), 0) FROM core.search_projection_mark WHERE tenant_id = ? AND search_index = ?",
                ps -> { ps.setLong(1, tenantId); ps.setString(2, searchIndex); },
                rs -> rs.getLong(1))
            .orElse(0L);
    }

    /**
     * The ids of every mark VISIBLE for this pair right now, oldest first — a full rebuild's cleanup
     * list (#969). {@code id} order is not commit order (#712): a mark whose transaction is still open
     * can carry an id BELOW the head yet be invisible here, and commit after the scan's snapshot
     * opened. Deleting {@code id <= head} at the end would then drop a mark the rebuilt generation
     * never reflected; deleting exactly the ids seen here (a subset of what the later scan snapshot
     * sees, since the scan opens after this read) cannot.
     */
    public List<Long> pendingMarkIds(long tenantId, String searchIndex) {
        return Jdbc.query(ds, "SearchProjectionMarkStore.pendingMarkIds",
            "SELECT id FROM core.search_projection_mark WHERE tenant_id = ? AND search_index = ? ORDER BY id",
            ps -> { ps.setLong(1, tenantId); ps.setString(2, searchIndex); },
            rs -> rs.getLong(1));
    }

    /**
     * Every (tenant, index) pair that currently has at least one undrained mark, in one query —
     * {@link io.lumnik.hub.search.projection.SearchProjectionWorker#discoveryTick}'s own per-tick
     * dispatch signal (the same N+1 shape {@link #validIndexNamesByEntityAndTenantAdmin} was already
     * fixed for one level up). A pair present here dispatches to catch-up regardless of its state
     * row's own {@code position} — a mark's physical presence in this table already IS "not yet
     * applied," whatever id order its producing transaction happened to commit in (#712: an earlier
     * design compared this table's max id against {@code position} instead, which silently dropped a
     * lower-id mark whose commit landed after a higher-id one had already advanced {@code position}
     * past it). A pair with zero marks is simply absent here.
     */
    private record PendingMarkRow(long tenantId, String searchIndex) {}

    public Map<Long, Set<String>> pendingMarkPairsAdmin() {
        List<PendingMarkRow> rows = Jdbc.query(ds, "SearchProjectionMarkStore.pendingMarkPairsAdmin",
            "SELECT DISTINCT tenant_id, search_index FROM core.search_projection_mark",
            ps -> {},
            rs -> new PendingMarkRow(rs.getLong(1), rs.getString(2)));

        Map<Long, Set<String>> byTenant = new LinkedHashMap<>();
        for (PendingMarkRow row : rows) {
            byTenant.computeIfAbsent(row.tenantId(), k -> new LinkedHashSet<>()).add(row.searchIndex());
        }
        return byTenant;
    }

    /** #730: whether the pair has ANY undrained mark right now — a fresh, per-pair check (not the
     *  tick-start snapshot {@link #pendingMarkPairsAdmin()}), because the freshness advance must
     *  read it AFTER it has fixed its bound. */
    public boolean hasPendingMarkAdmin(long tenantId, String searchIndex) {
        return Jdbc.queryOne(ds, "SearchProjectionMarkStore.hasPendingMarkAdmin",
                "SELECT EXISTS (SELECT 1 FROM core.search_projection_mark WHERE tenant_id = ? AND search_index = ?)",
                ps -> { ps.setLong(1, tenantId); ps.setString(2, searchIndex); },
                rs -> rs.getBoolean(1))
            .orElse(true); // no answer is treated as "pending": never advance on doubt
    }

    /** #894: whether the pair has more than {@code threshold} undrained marks — counted no further than
     *  {@code threshold + 1} rows, so the answer costs the same however large the backlog has grown. */
    public boolean pendingMarksExceed(long tenantId, String searchIndex, int threshold) {
        return Jdbc.queryOne(ds, "SearchProjectionMarkStore.pendingMarksExceed",
                "SELECT count(*) > ? FROM (SELECT 1 FROM core.search_projection_mark "
              + "WHERE tenant_id = ? AND search_index = ? LIMIT ?) backlog",
                ps -> { ps.setInt(1, threshold); ps.setLong(2, tenantId); ps.setString(3, searchIndex); ps.setLong(4, threshold + 1L); },
                rs -> rs.getBoolean(1))
            .orElse(false);
    }

    /** Every (tenant, index) pair's oldest undrained mark's own marked_at, deployment-wide — #703's own
     *  lag computation (design doc §1: "lag is computed live from the oldest undrained mark's marked_at,
     *  zero when none remain"). A pair absent here has zero lag. */
    private record OldestMarkRow(long tenantId, String searchIndex, Timestamp oldest) {}

    public Map<Long, Map<String, Instant>> oldestPendingMarkedAtAdmin() {
        List<OldestMarkRow> rows = Jdbc.query(ds, "SearchProjectionMarkStore.oldestPendingMarkedAtAdmin",
            "SELECT tenant_id, search_index, MIN(marked_at) FROM core.search_projection_mark "
          + "GROUP BY tenant_id, search_index",
            ps -> {},
            rs -> new OldestMarkRow(rs.getLong(1), rs.getString(2), rs.getTimestamp(3)));

        Map<Long, Map<String, Instant>> byTenant = new LinkedHashMap<>();
        for (OldestMarkRow row : rows) {
            byTenant.computeIfAbsent(row.tenantId(), k -> new LinkedHashMap<>())
                .put(row.searchIndex(), row.oldest().toInstant());
        }
        return byTenant;
    }

    /** {@link #oldestPendingMarkedAtAdmin()}, narrowed to one tenant — #703's own REST status door
     *  (Task 9), which reads the admin pool from a request path and must filter explicitly (no RLS
     *  here). */
    public Map<String, Instant> oldestPendingMarkedAtForTenant(long tenantId) {
        List<OldestMarkRow> rows = Jdbc.query(ds, "SearchProjectionMarkStore.oldestPendingMarkedAtForTenant",
            "SELECT tenant_id, search_index, MIN(marked_at) FROM core.search_projection_mark "
          + "WHERE tenant_id = ? GROUP BY tenant_id, search_index",
            ps -> ps.setLong(1, tenantId),
            rs -> new OldestMarkRow(rs.getLong(1), rs.getString(2), rs.getTimestamp(3)));
        Map<String, Instant> byIndex = new LinkedHashMap<>();
        for (OldestMarkRow row : rows) byIndex.put(row.searchIndex(), row.oldest().toInstant());
        return byIndex;
    }

    /**
     * Incremental catch-up's own batch fetch (§5c step 1, corrected per #712): the oldest {@code
     * limit} undrained marks for this pair, oldest id first — never filtered against the state row's
     * own {@code position}. Every row in this table is by construction not yet applied (an applied
     * row is deleted, {@link #deleteByExactIds}), so its presence alone is the correctness signal;
     * filtering by {@code id > position} silently dropped a lower-id mark whose producing transaction
     * committed only after a higher-id one had already advanced {@code position} past it.
     */
    public List<MarkRow> fetchBatch(long tenantId, String searchIndex, int limit) {
        return Jdbc.query(ds, "SearchProjectionMarkStore.fetchBatch",
            "SELECT id, mark_kind, canonical_key FROM core.search_projection_mark "
          + "WHERE tenant_id = ? AND search_index = ? ORDER BY id LIMIT ?",
            ps -> { ps.setLong(1, tenantId); ps.setString(2, searchIndex); ps.setInt(3, limit); },
            rs -> new MarkRow(rs.getLong(1), MarkKind.fromSql(rs.getString(2)), rs.getString(3)));
    }

    /** Purge-drain's own cleanup (§5d): every mark left for a (tenant, search index) pair whose
     *  declaration is already gone — they would otherwise sit orphaned forever, or get misread
     *  against a same-named index's fresh future declaration. */
    public void deleteAllFor(long tenantId, String searchIndex) {
        Jdbc.update(ds, "SearchProjectionMarkStore.deleteAllFor",
            "DELETE FROM core.search_projection_mark WHERE tenant_id = ? AND search_index = ?",
            ps -> { ps.setLong(1, tenantId); ps.setString(2, searchIndex); });
    }

    /**
     * Deletes by the EXACT ids the caller already fetched — never a live-recomputed condition (e.g.
     * {@code id <= max}). A row concurrently reissued a fresher id between the caller's own
     * {@link #fetchBatch} and this delete (design doc §1's re-mark decision) simply matches
     * zero rows here: it is never lost, and the fresher id sits above whatever this pair's
     * diagnostic {@code position} counter advances to, so the very next {@link #fetchBatch} still
     * picks it up whole (#712: {@code fetchBatch} has no position filter to begin with). Returns the
     * highest id actually deleted, or 0 when none of {@code ids} matched (the caller must not treat
     * 0 as "advance to zero" — see {@code SearchProjectionWorker.runCatchUp}).
     */
    public long deleteByExactIds(long tenantId, String searchIndex, List<Long> ids) {
        if (ids.isEmpty()) return 0L;
        try (Connection c = ds.getConnection()) {
            return deleteByExactIds(c, tenantId, searchIndex, ids);
        } catch (SQLException e) {
            throw new RuntimeException("SearchProjectionMarkStore.deleteByExactIds: " + e.getMessage(), e);
        }
    }

    /** {@link #deleteByExactIds(long, String, List)}, on the caller's own open connection/transaction —
     *  {@code SearchProjectionWorker.runBuild}'s atomic mark-cleanup + markReady tail (I3 fix, #969). */
    public long deleteByExactIds(Connection c, long tenantId, String searchIndex, List<Long> ids) throws SQLException {
        if (ids.isEmpty()) return 0L;
        long maxDeleted = 0L;
        try (PreparedStatement ps = c.prepareStatement(
                 "DELETE FROM core.search_projection_mark WHERE tenant_id = ? AND search_index = ? "
               + "AND id = ANY(?) RETURNING id")) {
            ps.setLong(1, tenantId);
            ps.setString(2, searchIndex);
            ps.setArray(3, c.createArrayOf("bigint", ids.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) maxDeleted = Math.max(maxDeleted, rs.getLong(1));
            }
        }
        return maxDeleted;
    }
}
