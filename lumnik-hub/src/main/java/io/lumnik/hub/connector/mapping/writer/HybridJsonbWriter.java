package io.lumnik.hub.connector.mapping.writer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.JsonPathException;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.ReadContext;
import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.common.text.Text;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.entity.NewWriterScopes;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.PromotedColumn;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.SqlType;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.Transform;
import io.lumnik.hub.event.RowEventGate;
import io.lumnik.hub.sql.TemporalText;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.lumnik.hub.sql.SqlIdentifiers.keptName;
import static io.lumnik.hub.sql.SqlIdentifiers.quoteIdent;
import static io.lumnik.hub.sql.SqlIdentifiers.quoteTable;
import static java.util.stream.Collectors.joining;

/**
 * Writes REST connector payloads into hybrid Postgres tables:
 * promoted typed columns + raw {@code data JSONB} + standard lineage columns.
 *
 * <p>Uses the {@code admin} datasource for all DDL and DML so that:
 * <ul>
 *   <li>DDL executes without RLS restrictions.
 *   <li>Inserts bypass RLS (admin BYPASSRLS in production; same user in tests).
 * </ul>
 *
 * <p>Auto-creates the table on first write. Adds missing promoted columns on drift.
 * Upserts via {@code ON CONFLICT (tenant_id, external_id)}.
 */
@ApplicationScoped
public class HybridJsonbWriter {

    /** The per-row dedup column this writer retrofits and binds. */
    private static final String ROW_HASH = "row_hash";

    private static final Configuration JP =
        Configuration.defaultConfiguration().addOptions(Option.SUPPRESS_EXCEPTIONS);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    TransactionSynchronizationRegistry txSync;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    @Inject
    DynamicTableRegistry registry;

    @Inject
    RowEventGate gate;

    /** undefined_table, undefined_column: the SQLSTATEs of a write into a table that is no longer what was checked. */
    private static final Set<String> TABLE_GONE = Set.of("42P01", "42703");

    /** The columns of each table this process has made or checked (#2010) — forgotten on a drop, or by tests. */
    private final Map<String, Set<String>> schemaCache = new ConcurrentHashMap<>();

    /** Once-per-boot registration marker per (table, tenant) — the registry upsert is idempotent,
     *  this only spares one round-trip per batch. Cleared with the schema cache. */
    private final Set<String> registered = ConcurrentHashMap.newKeySet();

    /**
     * Write a batch of items to the target table described by {@code spec}.
     * The table is created automatically on first call.
     *
     * @param spec        table specification (name, key path, promoted columns)
     * @param items       raw items (Map representations of JSON objects)
     * @param tenantId    current tenant ID stored in every row
     * @param connectorId connector lineage column
     * @param endpointId  endpoint lineage column
     */
    public long write(HybridJsonbSpec spec, List<Map<String, Object>> items,
                      long tenantId, UUID connectorId, UUID endpointId) {
        return write(spec, items, null, tenantId, connectorId, endpointId);
    }

    /**
     * Full write: {@code systemContexts} (aligned with {@code items}) supply {@code _row_hash} —
     * the row identity the RAG/table targets already persist and this branch used to throw away
     * (C4/P18 PR1). {@code null} contexts bind NULL hashes (direct/test callers).
     *
     * @return the promoted cells that did not read as their column's type, written NULL (#1398)
     */
    public long write(HybridJsonbSpec spec, List<Map<String, Object>> items,
                      List<Map<String, Object>> systemContexts,
                      long tenantId, UUID connectorId, UUID endpointId) {
        return write(spec, items, null, systemContexts, tenantId, connectorId, endpointId);
    }

    /**
     * @param cells the promoted columns a mapping computed, aligned with {@code items} (#1617): column values are read
     *              from them by name and converted to their type, {@code data} and the key from the item. {@code null}:
     *              each column is read from its item by its path, as without a mapping.
     */
    public long write(HybridJsonbSpec spec, List<Map<String, Object>> items, List<Map<String, Object>> cells,
                      List<Map<String, Object>> systemContexts,
                      long tenantId, UUID connectorId, UUID endpointId) {
        if (items.isEmpty()) return 0;
        if (cells != null) refuseUndeclared(spec, cells);
        // Pre-compile JsonPath expressions once per write call to avoid recompilation per row
        JsonPath uniqueKey = JsonPath.compile(spec.uniqueKeyPath());
        List<JsonPath> colPaths = paths(spec);
        String[] target = schemaAndTable(spec.table());
        String entity = target[0] + "." + target[1];
        // Asked once per write, as the dynamic writer does: no subscription, no events, today's write (#1816).
        boolean emitting = gate.enabled(tenantId, entity);
        try (Connection c = adminDs.getConnection()) {
            boolean[] locked = {false};
            long unreadable;
            try {
                boolean schemaChanged = ensureTable(c, spec, locked);
                unreadable = writeLocked(c, spec, schemaChanged, items, cells, systemContexts, tenantId, connectorId,
                    endpointId, uniqueKey, colPaths, emitting ? entity : null);
            } catch (SQLException | RuntimeException | Error failure) {
                // A missing table or column: another replica dropped it. The next write checks it again; a failure on
                // the data keeps the table checked, and the writes after it never wait.
                String state = Jdbc.sqlStateOf(failure);
                if (state != null && TABLE_GONE.contains(state)) forget(spec.table());
                // A failed Jdbc.transaction may end the session: the release then throws, and must not mask it.
                if (locked[0]) {
                    try {
                        DynamicTableWriter.releaseWriteLock(c, target[0], target[1]);
                    } catch (SQLException releaseFailure) {
                        failure.addSuppressed(releaseFailure);
                    }
                }
                throw failure;
            }
            DynamicTableWriter.releaseWriteLock(c, target[0], target[1]);
            return unreadable;
        } catch (SQLException e) {
            throw new RuntimeException("HybridJsonbWriter failed for " + spec.table(), e);
        }
    }

    /**
     * Each item's promoted columns, read by their paths and transformed, before a mapping runs over them (#1617).
     * Every column with a path is in the row, null when the item has no value for it — as every header is in a csv
     * row. A column without a path is the mapping's to fill: absent until a hook writes it. A value its transform
     * cannot read is null, and counted on {@code unreadableAt} with its item's index.
     */
    public static List<Map<String, Object>> promoted(HybridJsonbSpec spec, List<Map<String, Object>> items,
                                                     IntConsumer unreadableAt) {
        return promoted(spec.promote(), items, unreadableAt);
    }

    /** {@link #promoted(HybridJsonbSpec, List, IntConsumer)} over a bare promote list — kafka's (#1777). */
    public static List<Map<String, Object>> promoted(List<PromotedColumn> promote, List<Map<String, Object>> items,
                                                     IntConsumer unreadableAt) {
        List<JsonPath> colPaths = paths(promote);
        List<Map<String, Object>> rows = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            ReadContext ctx = JsonPath.using(JP).parse(json(items.get(i)));
            Map<String, Object> row = new LinkedHashMap<>();
            for (int ci = 0; ci < colPaths.size(); ci++) {
                if (colPaths.get(ci) == null) continue;
                Object val = pathValue(ctx, colPaths.get(ci), promote.get(ci));
                if (val == UNREADABLE) unreadableAt.accept(i);
                row.put(promote.get(ci).name(), val == UNREADABLE ? null : val);
            }
            rows.add(row);
        }
        return rows;
    }

    /** A column read by its path and transformed; {@code null} without a path, {@link #UNREADABLE} if unreadable. */
    private static Object pathValue(ReadContext ctx, JsonPath path, PromotedColumn col) {
        if (path == null) return null;
        Object raw;
        try {
            raw = ctx.read(path, Object.class);
        } catch (JsonPathException unreadable) {
            return UNREADABLE;   // a function over an empty array throws, whatever the options (#1620): one cell
        }
        Object value = oneValue(raw, path, col);
        return value == UNREADABLE ? value : transformed(value, col);
    }

    /**
     * A path with a filter or a wildcard selects a list, even of one value (#1619): it reads that value, nothing when
     * the list is empty, and several values are one unreadable cell — the rule a pagination path follows (#1611).
     * A path without one keeps what it points at, an array included; so does a {@code jsonb} column.
     */
    private static Object oneValue(Object raw, JsonPath path, PromotedColumn col) {
        if (path.isDefinite() || col.type() == SqlType.JSONB || !(raw instanceof List<?> values)) return raw;
        return switch (values.size()) {
            case 0 -> null;
            case 1 -> values.getFirst();
            default -> UNREADABLE;
        };
    }

    /** A mapping writes only the columns the table has: each one a promote entry declares (#1617). */
    private static void refuseUndeclared(HybridJsonbSpec spec, List<Map<String, Object>> cells) {
        Set<String> declared = new HashSet<>();
        for (PromotedColumn col : spec.promote()) declared.add(col.name());
        for (Map<String, Object> row : cells) {
            if (declared.containsAll(row.keySet())) continue;
            String column = row.keySet().stream().filter(c -> !declared.contains(c)).findFirst().orElseThrow();
            throw new MappingValidationException("the mapping writes column '" + column + "', which no promote entry "
                + "declares — declare it in target.promote with its type, without a path");
        }
    }

    /** One compiled path per promoted column, {@code null} for a column a mapping fills. */
    private static List<JsonPath> paths(HybridJsonbSpec spec) {
        return paths(spec.promote());
    }

    private static List<JsonPath> paths(List<PromotedColumn> promote) {
        List<JsonPath> paths = new ArrayList<>(promote.size());
        for (PromotedColumn p : promote) paths.add(p.jsonPath() == null ? null : JsonPath.compile(p.jsonPath()));
        return paths;
    }

    private static String json(Map<String, Object> item) {
        try {
            return JSON.writeValueAsString(item);
        } catch (Exception e) {
            throw new IllegalStateException("HybridJsonbWriter: JSON serialization failed", e);
        }
    }

    /**
     * @param schemaChanged whether {@link #ensureTable} made the table or changed its columns
     * @param eventEntity the entity of the staged events, {@code null} when no subscription asked for them
     */
    private long writeLocked(Connection c, HybridJsonbSpec spec, boolean schemaChanged, List<Map<String, Object>> items,
                             List<Map<String, Object>> cells,
                             List<Map<String, Object>> systemContexts, long tenantId, UUID connectorId,
                             UUID endpointId, JsonPath uniqueKey, List<JsonPath> colPaths, String eventEntity)
        throws SQLException {
        if (schemaChanged) registered.removeIf(k -> k.startsWith(spec.table() + "|" + tenantId + "|"));
        registerIfNeeded(spec, tenantId, connectorId, c);
        if (eventEntity != null) {
            return writeEmitting(c, eventEntity, spec, items, cells, systemContexts,
                tenantId, connectorId, endpointId, uniqueKey, colPaths);
        }
        // One row per statement on purpose: this chunk is not reduced to one item per key, and a multi-row upsert
        // that meets a key twice fails ("cannot affect row a second time"). The gated path reduces it first.
        long unreadable = 0;
        try (PreparedStatement ps = c.prepareStatement(buildInsertSql(spec, 1))) {
            for (int r = 0; r < items.size(); r++) {
                unreadable += bind(ps, spec, colPaths, readItem(items.get(r), uniqueKey, spec),
                     cells == null ? null : cells.get(r), rowHashOf(systemContexts, r), tenantId, connectorId,
                     endpointId);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        return unreadable;
    }

    /**
     * The write a subscription asked for (#1816): the upserts and their events in one transaction, on the connection
     * that holds the table's write lock. Every item is read, so {@code unreadable} counts the whole chunk; only the
     * last item of each key runs, so a shadowed version raises no event. An event is a new row or a moved, non-NULL
     * hash: a promoted column rewritten under the same hash is not a change at the source.
     *
     * <p>The rows run in multi-row statements (#1819), {@link DynamicTableWriter#subBatchSize} rows each, as the
     * dynamic writer's do: one round trip per statement, not per row. A statement returns the rows it wrote, each with
     * its hash, whether it is new, its previous hash and its id; a row left alone returns nothing. The chunk holds
     * each key once, so no statement touches a row twice.
     *
     * <p>Writes that emit events for one table and tenant are serialised: the transaction first takes a lock on
     * {@link #rowEventsLockKey}, held until it ends. Its statements start after the previous writer committed, so they
     * see its rows. This relies on READ COMMITTED: each statement takes a fresh snapshot, after the lock is granted;
     * under REPEATABLE READ the snapshot would be taken before the wait. The shared write lock is taken before this
     * lock, always in that order.
     */
    private long writeEmitting(Connection c, String entity, HybridJsonbSpec spec,
                               List<Map<String, Object>> items, List<Map<String, Object>> cells,
                               List<Map<String, Object>> systemContexts, long tenantId, UUID connectorId,
                               UUID endpointId, JsonPath uniqueKey, List<JsonPath> colPaths) {
        List<ParsedItem> parsed = new ArrayList<>(items.size());
        boolean[] shadowed = new boolean[items.size()];
        Map<String, Integer> lastOf = new HashMap<>();
        for (int r = 0; r < items.size(); r++) {
            parsed.add(readItem(items.get(r), uniqueKey, spec));
            Integer earlier = lastOf.put(parsed.get(r).externalId(), r);
            if (earlier != null) shadowed[earlier] = true;
        }
        // Read before the transaction: a shadowed item is counted unreadable though it never runs.
        long unreadable = 0;
        List<Integer> live = new ArrayList<>(items.size());
        Object[][] values = new Object[items.size()][];
        for (int r = 0; r < items.size(); r++) {
            Cells row = cellsOf(spec, colPaths, parsed.get(r), cells == null ? null : cells.get(r));
            unreadable += row.unreadable();
            values[r] = row.values();
            if (!shadowed[r]) live.add(r);
        }
        String source = connectorId != null ? connectorId.toString() : "ingest";
        UUID runId = systemContexts == null ? null : (UUID) systemContexts.getFirst().get("_run_id");
        int sub = DynamicTableWriter.subBatchSize(spec.promote().size() + FIXED_PARAMS);
        // The label names the table, as the un-gated path's wrapper does: the SQLSTATE's message names none.
        Jdbc.transaction(c, "HybridJsonbWriter.write " + spec.table(), tx -> {
            // The lock is the statement's effect; its one (void) row is read and dropped.
            Jdbc.queryOne(tx, "HybridJsonbWriter.rowEventsLock", "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                ps -> ps.setString(1, rowEventsLockKey(entity, tenantId)), rs -> true);
            try (PreparedStatement outbox = tx.prepareStatement(DynamicTableWriter.OUTBOX_SQL)) {
                for (int start = 0; start < live.size(); start += sub) {
                    int end = Math.min(start + sub, live.size());
                    try (PreparedStatement ps = tx.prepareStatement(buildInsertSql(spec, end - start) + returningChange(spec))) {
                        int base = 0;
                        for (int k = start; k < end; k++) {
                            int r = live.get(k);
                            bindRow(ps, base, spec, parsed.get(r), values[r], rowHashOf(systemContexts, r),
                                tenantId, connectorId, endpointId);
                            base += spec.promote().size() + FIXED_PARAMS;
                        }
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {   // a row left unchanged by the upsert's WHERE returns nothing
                                String hash = rs.getString(1);
                                boolean inserted = rs.getBoolean(2);
                                if (hash != null && (inserted || !hash.equals(rs.getString(3)))) {
                                    DynamicTableWriter.stageEvent(outbox, JSON, tenantId, source, entity, hash, runId,
                                        inserted ? "insert" : "update", rs.getLong(4));
                                }
                            }
                        }
                    }
                    // Per statement, so the outbox batch never outgrows one sub-batch.
                    outbox.executeBatch();
                }
            }
            return null;
        });
        return unreadable;
    }

    /** The key of the lock event writes of one table and tenant take; never the table's own write-lock key. */
    static String rowEventsLockKey(String entity, long tenantId) {
        return "row-events:" + entity + ":" + tenantId;
    }

    private static String rowHashOf(List<Map<String, Object>> systemContexts, int r) {
        return systemContexts == null ? null : (String) systemContexts.get(r).get("_row_hash");
    }

    /** {@code [schema, table]} of a target, which the record holds to {@code schema.table} (#1989). */
    private static String[] schemaAndTable(String table) {
        return table.split("\\.", 2);
    }

    /**
     * Register the hybrid table in {@code connector.dynamic_table} so scope discovery sees it —
     * the missing link that kept every hybrid scope un-answerable (HybridTargetSchemaCardIT).
     * Key {@code hybrid:<table>}: unique per endpoint target, so two endpoints of one mapping
     * write two tables and get two registry rows. For connector runs the card appears when the completed RUN
     * triggers rediscover; the run-less one-shot CSV path rediscovers its own scopes after
     * its ingest commits (spec 2026-09-02) — one command, an askable table.
     */
    /**
     * @param c the write's connection, which holds the table's write lock — registering on it, never
     *          on a second connection, keeps a wait on the registry visible to Postgres's deadlock
     *          detector (a delete of this connector holds its registry rows while it waits for the
     *          lock); {@code null} outside a write, on its own connection
     */
    private void registerIfNeeded(HybridJsonbSpec spec, long tenantId, UUID connectorId, Connection c) {
        // per connector (#1186): a second connector writing the same table registers it too
        String key = spec.table() + "|" + tenantId + "|" + connectorId;
        if (registered.contains(key)) return;
        String[] parts = schemaAndTable(spec.table());
        String schema = parts[0];
        String table  = parts[1];
        // Business columns ONLY — every entry here becomes a queryable column on the scope's
        // schema card, surfaced to the LLM. Lineage (row_hash, tenant_id, endpoint_id…) stays
        // out, same contract as DynamicTableWriter registering just the inferred columns.
        Map<String, String> cols = new LinkedHashMap<>();
        cols.put("external_id", "TEXT");
        for (PromotedColumn col : spec.promote()) cols.put(col.name(), col.type().ddl);
        cols.put("data", "JSONB");
        String mapping = DynamicTableRegistry.HYBRID_MAPPING_PREFIX + spec.table();
        // #2048: judged and recorded at once, before any row, on every path, the retry after commit included. The
        // writer's row stays FOR SHARE until the registration commits, so a re-tag either waits for it or is seen by
        // it. A refusal, or a check that could not complete, stops the write; so does a failed registration of a
        // connector table, whose writers the entity checks read from the registry. Only an ext table's registration
        // may fail quietly, back to its savepoint, and be retried.
        Jdbc.TxFunction<RuntimeException> judgeAndRegister = tx -> {
            NewWriterScopes.requireNewWriterWithinReaders(tx, tenantId, schema, table, connectorId);
            Savepoint before = tx.setSavepoint();
            try {
                registry.register(tx, tenantId, mapping, schema, table, cols, connectorId);
                return null;
            } catch (RuntimeException failed) {
                tx.rollback(before);
                return failed;
            }
        };
        RuntimeException e = c == null
            ? Jdbc.transaction(adminDs, "HybridJsonbWriter.registerIfNeeded", judgeAndRegister)
            : Jdbc.transaction(c, "HybridJsonbWriter.registerIfNeeded", judgeAndRegister);
        if (e == null) {
            registered.add(key);
        } else if (DynamicTableRegistry.DYNAMIC_SCHEMA.equals(schema)) {
            throw new ConnectorException(spec.table() + ": its writer could not be recorded, so no row is written "
                + "(the entities reading it learn their writers from that record): "
                + Jdbc.failureText("HybridJsonbWriter.registerIfNeeded", e), e);
        } else {
            // An ext table's registration never fails a data write: no entity reads it. The known case:
            // the one-shot CSV path writes rows INSIDE the transaction that creates its connector row, so the registry
            // FK (dynamic_table_connector_fk, admin DS, outside that tx) cannot see it yet. It
            // used to stay unregistered — "benign" for discovery (a one-shot is un-askable by
            // design), but the DELETE purge walks the registry, so the unregistered table
            // survived its connector as an orphan. When a transaction is active, retry once
            // after it commits: the FK target is visible then, and the delete promise holds.
            // No commit (or a second failure) leaves it unregistered as before.
            registered.remove(key);
            if (txSync.getTransactionKey() != null) {
                txSync.registerInterposedSynchronization(new Synchronization() {
                    @Override public void beforeCompletion() { /* nothing to flush */ }
                    @Override public void afterCompletion(int status) {
                        if (status == Status.STATUS_COMMITTED) {
                            registerIfNeeded(spec, tenantId, connectorId, null);
                        }
                    }
                });
            }
            Logger.getLogger(HybridJsonbWriter.class)
                .infof("hybrid table %s not registered for discovery (will retry on next write): %s",
                       spec.table(), e.getMessage());
        }
    }

    // ── Schema management ────────────────────────────────────────────────────

    /** The {@code CREATE TABLE}: the reserved columns ({@link HybridJsonbSpec#RESERVED_COLUMNS}) with the promoted ones between. */
    static String createTableSql(String qfqTable, List<PromotedColumn> promote) {
        // No IF NOT EXISTS (#2010): a table that appeared since the check fails the creation, never gets recorded.
        StringBuilder sb = new StringBuilder("CREATE TABLE ").append(qfqTable).append(" (");
        for (TableColumn col : HybridJsonbSpec.LEADING_COLUMNS) sb.append(col.sql()).append(", ");
        for (PromotedColumn col : promote) {
            sb.append(qi(col.name())).append(' ').append(col.type().ddl).append(", ");
        }
        for (TableColumn col : HybridJsonbSpec.TRAILING_COLUMNS) sb.append(col.sql()).append(", ");
        return sb.append("UNIQUE (tenant_id, external_id))").toString();
    }

    /**
     * Makes the table ready for this write, and returns holding its shared write lock ({@code locked} turns true once it
     * is taken): whether the table was made or its columns changed, so the registry entry follows them, as
     * DynamicTableWriter re-registers on evolve.
     *
     * <p>A table this process has made or checked, with every promoted column, takes the shared lock only: writers
     * never wait on each other. Otherwise (#2010), in ONE transaction under the exclusive first-write lock: a missing
     * table is made whole (table, isolation, grants) and recorded as the hub's; an existing one is the hub's or is
     * refused, then repaired and given its new columns. A failure rolls everything back, so no replica ever sees a
     * table without its isolation, and the next write tries again. The cost: that transaction waits for the writes in
     * flight on the table, once per process and table, and again on each new column.
     */
    private boolean ensureTable(Connection c, HybridJsonbSpec spec, boolean[] locked) throws SQLException {
        String[] target = schemaAndTable(spec.table());
        Set<String> known = schemaCache.get(spec.table());
        // The catalog keeps a long column name's first 63 bytes, and so does the cache that read it.
        if (known != null && spec.promote().stream().allMatch(col -> known.contains(keptName(col.name())))) {
            DynamicTableWriter.writeLock(c, target[0], target[1]);
            locked[0] = true;
            return false;
        }
        boolean changed = Jdbc.transaction(c, "HybridJsonbWriter.ensureTable " + spec.table(), tx -> {
            DynamicTableWriter.firstWriteLock(tx, target[0], target[1]);
            boolean made = !UnseenRowSweeper.exists(tx, fqn(spec.table()));
            if (made) {
                createTable(tx, spec);
                DynamicTableRegistry.recordHybridTable(tx, target[0], target[1]);
            }
            boolean added = !made && adopt(tx, spec, target[0], target[1]);
            // Taken before the commit lets the exclusive lock go, and held by the session after it: no connector delete
            // drops the table between this check and the write.
            DynamicTableWriter.writeLock(tx, target[0], target[1]);
            locked[0] = true;
            return made || added;
        });
        // Read under the shared lock: a column changes under the exclusive one only.
        schemaCache.put(spec.table(), loadColumns(c, spec.table()));
        return changed;
    }

    /**
     * The policy {@link RlsDdl#tenantIsolationPolicy} creates, as this server's {@code pg_get_expr} renders it: a {@code
     * tenant_isolation} with any other content is replaced (#2010). Read once per process from a temporary table, so a
     * server that renders it otherwise does not see every healthy table as wrong.
     */
    private volatile String tenantIsolationQual;

    private String tenantIsolationQual(Connection c) throws SQLException {
        if (tenantIsolationQual == null) {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TEMP TABLE lumnik_policy_probe (tenant_id BIGINT) ON COMMIT DROP");
                s.execute(RlsDdl.tenantIsolationPolicy(null, "lumnik_policy_probe"));
            }
            tenantIsolationQual = Jdbc.queryOne(c, "HybridJsonbWriter.tenantIsolationQual", "SELECT pg_get_expr(polqual, "
                + "polrelid) FROM pg_policy WHERE polrelid = 'pg_temp.lumnik_policy_probe'::regclass", ps -> {},
                rs -> rs.getString(1)).orElseThrow();
        }
        return tenantIsolationQual;
    }

    /**
     * Whether a table's RLS is on, its {@code tenant_isolation} is the hub's (for every command, every role, no
     * {@code WITH CHECK}), and the first other permissive policy, OR-ed with it: each tenant would see what that one
     * lets through.
     */
    private static final String ISOLATION_SQL = "SELECT t.relrowsecurity, EXISTS (SELECT 1 FROM pg_policy p "
        + "WHERE p.polrelid = t.oid AND p.polname = 'tenant_isolation' AND p.polpermissive AND p.polcmd = '*' "
        + "AND p.polroles = '{0}' AND p.polwithcheck IS NULL AND pg_get_expr(p.polqual, p.polrelid) = ?), "
        + "(SELECT min(p.polname) FROM pg_policy p WHERE p.polrelid = t.oid AND p.polpermissive "
        + "AND p.polname <> 'tenant_isolation') FROM pg_class t WHERE t.oid = to_regclass(?)";

    private record Isolation(boolean rowSecurity, boolean policed, String widenedBy) {}

    /**
     * An existing table: the hub's own ({@link DynamicTableRegistry#ownsHybridTable}), or refused before any DDL touches
     * it (#1989): under {@code connector} sit the hub's own tables, and a target may name one. A policy the hub did not
     * make is refused. Then repaired: RLS and the hub's {@code tenant_isolation} only where missing (each takes the
     * table's ACCESS EXCLUSIVE lock, readers included), the grants, the columns the spec adds and the ones an old table
     * lacks, the row-hash index.
     *
     * @return whether a column was added
     */
    private boolean adopt(Connection c, HybridJsonbSpec spec, String schema, String table) throws SQLException {
        String rel = fqn(spec.table());
        if (!DynamicTableRegistry.ownsHybridTable(c, schema, table)) {
            throw HybridTargetRefusedException.notMadeByTheHub(spec.table());
        }
        String reference = tenantIsolationQual(c);
        Isolation isolation = Jdbc.queryOne(c, "HybridJsonbWriter.isolation", ISOLATION_SQL, ps -> {
            ps.setString(1, reference);
            ps.setString(2, rel);
        }, rs -> new Isolation(rs.getBoolean(1), rs.getBoolean(2), rs.getString(3))).orElseThrow();
        if (isolation.widenedBy() != null) throw HybridTargetRefusedException.widenedBy(spec.table(), isolation.widenedBy());
        Set<String> known = loadColumns(c, spec.table());
        boolean changed = false;
        try (Statement s = c.createStatement()) {
            if (!isolation.rowSecurity()) s.execute(RlsDdl.enableRowLevelSecurity(schema, table));
            if (!isolation.policed()) {
                s.execute("DROP POLICY IF EXISTS tenant_isolation ON " + rel);
                s.execute(RlsDdl.tenantIsolationPolicy(schema, table));
            }
            grant(s, schema, table);
            for (PromotedColumn col : spec.promote()) {
                if (known.contains(keptName(col.name()))) continue;
                s.execute("ALTER TABLE " + rel + " ADD COLUMN IF NOT EXISTS " + qi(col.name()) + " " + col.type().ddl);
                changed = true;
            }
            // Retrofit: tables created before C4/P18 PR1 lack row_hash; before #1873, its index.
            if (!known.contains(ROW_HASH)) {
                s.execute("ALTER TABLE " + rel + " ADD COLUMN IF NOT EXISTS " + ROW_HASH + " TEXT");
                changed = true;
            }
            if (!UnseenRowSweeper.exists(c, qi(schema) + "." + qi(indexName(table, "row_hash")))) {
                s.execute(rowHashIndexSql(rel, table));
            }
        }
        return changed;
    }

    /** The citation's drill-down reads a row by {@code (tenant_id, row_hash)} (#1873): one user read, but a seq scan without it. */
    private String rowHashIndexSql(String qfqTable, String tableName) {
        return "CREATE INDEX IF NOT EXISTS " + qi(indexName(tableName, "row_hash"))
            + " ON " + qfqTable + " (tenant_id, " + ROW_HASH + ")";
    }

    /**
     * The table's columns, from the catalog by its relation: {@code information_schema} would look a long name up whole,
     * where Postgres keeps its first 63 characters, and hides a column the role has no privilege on.
     */
    private Set<String> loadColumns(Connection c, String fqTable) {
        return Set.copyOf(Jdbc.query(c, "HybridJsonbWriter.loadColumns", "SELECT attname FROM pg_catalog.pg_attribute "
            + "WHERE attrelid = to_regclass(?) AND attnum > 0 AND NOT attisdropped", ps -> ps.setString(1, fqn(fqTable)),
            rs -> rs.getString(1)));
    }

    /**
     * The new table's DDL, run inside {@link #ensureTable}'s transaction: the table, its indexes, the tenant isolation
     * the connector.t_* tables get ({@link RlsDdl}), its grants. Its schema exists: V98 creates {@code ext} and grants
     * its USAGE. Package-visible so the emitted DDL can be pinned without a live datasource.
     */
    void createTable(Connection c, HybridJsonbSpec spec) throws SQLException {
        String[] parts = schemaAndTable(spec.table());
        String schemaName = parts[0];
        String tableName  = parts[1];
        String qfqTable   = fqn(spec.table());
        try (Statement s = c.createStatement()) {
            s.execute(createTableSql(qfqTable, spec.promote()));
            String idxName = indexName(tableName, "data_gin");
            s.execute("CREATE INDEX IF NOT EXISTS " + qi(idxName) +
                      " ON " + qfqTable + " USING GIN (data jsonb_path_ops)");
            s.execute(rowHashIndexSql(qfqTable, tableName));
            s.execute(RlsDdl.enableRowLevelSecurity(schemaName, tableName));
            s.execute(RlsDdl.tenantIsolationPolicy(schemaName, tableName));
            grant(s, schemaName, tableName);
        }
    }

    /** The grants a table lacks ({@link RlsDdl#hybridGrants}); any failure but a missing role rolls the DDL back. */
    private static void grant(Statement s, String schema, String table) throws SQLException {
        for (String sql : RlsDdl.hybridGrants(schema, table)) s.execute(sql);
    }

    // ── SQL building ─────────────────────────────────────────────────────────

    /** Bind parameters of a row besides its promoted columns: tenant, key, hash, data, connector, endpoint. */
    static final int FIXED_PARAMS = 6;

    /** The upsert of {@code rows} rows in one statement; {@code rows} of them tuples of the same shape. */
    private String buildInsertSql(HybridJsonbSpec spec, int rows) {
        List<String> cols = new ArrayList<>();
        List<String> qs   = new ArrayList<>();

        cols.add("tenant_id");    qs.add("?");
        cols.add("external_id");  qs.add("?");
        for (PromotedColumn col : spec.promote()) {
            cols.add(qi(col.name())); qs.add("?");
        }
        cols.add(ROW_HASH);     qs.add("?");
        cols.add("data");         qs.add("?::jsonb");
        cols.add("connector_id"); qs.add("?");
        cols.add("endpoint_id");  qs.add("?");

        // Every column the UPDATE copies from the incoming row, data aside (row_hash stands for it).
        List<String> copied = new ArrayList<>();
        copied.add(ROW_HASH);
        for (PromotedColumn col : spec.promote()) copied.add(qi(col.name()));
        String set      = copied.stream().map(c -> c + " = EXCLUDED." + c).collect(joining(", "));
        String stored   = copied.stream().map(c -> "cur." + c).collect(joining(", "));
        String incoming = copied.stream().map(c -> "EXCLUDED." + c).collect(joining(", "));

        // A row that came back unchanged is left alone (#903): a full-mode endpoint re-reads every
        // row on every run, and an unconditional UPDATE made each one a new tuple version — a new
        // TOAST value, index entries, a dead tuple, and the WAL for all of it. Unchanged means every
        // column the SET writes keeps its value: row_hash stands for data (both are the payload),
        // and the promoted columns are compared themselves, since a spec change (a column promoted
        // later, a new transform) moves them without moving the hash. A NULL incoming hash says
        // nothing about the payload, so it always writes. indexed_at thus marks the last change,
        // not the last read. A skipped row is still locked, so it still logs a small lock record.
        return "INSERT INTO " + fqn(spec.table()) + " AS cur" +
               " (" + String.join(", ", cols) + ")" +
               " VALUES " + String.join(", ", Collections.nCopies(rows, "(" + String.join(", ", qs) + ")")) +
               " ON CONFLICT (tenant_id, external_id) DO UPDATE SET " + set +
               ", data = EXCLUDED.data, indexed_at = now()" +
               " WHERE EXCLUDED." + ROW_HASH + " IS NULL" +
               " OR (" + stored + ") IS DISTINCT FROM (" + incoming + ")";
    }

    /**
     * What the gated upsert returns for a row it wrote: its hash, whether it is new, its previous hash as the
     * statement's snapshot saw it, and its id, which the event carries so the row is read back by primary key. An
     * event needs nothing else of the row, so the rows of a multi-row statement need no matching to their items. The
     * previous hash is exact: writes that emit events for one table and tenant are serialised
     * ({@link #writeEmitting}), so no other one commits the same key after that snapshot.
     */
    private static String returningChange(HybridJsonbSpec spec) {
        return " RETURNING cur." + ROW_HASH + ", (cur.xmax = 0), (SELECT stored." + ROW_HASH
            + " FROM " + fqn(spec.table())
            + " stored WHERE stored.tenant_id = cur.tenant_id AND stored.external_id = cur.external_id), cur.id";
    }

    // ── Row binding ──────────────────────────────────────────────────────────

    /** An item read once: its JSON, the context its paths read, and its key. */
    private record ParsedItem(String json, ReadContext ctx, String externalId) {}

    private static ParsedItem readItem(Map<String, Object> item, JsonPath uniqueKey, HybridJsonbSpec spec) {
        // Serialize to JSON string first so JsonPath operates on a well-known input type
        String jsonStr = json(item);
        ReadContext ctx = JsonPath.using(JP).parse(jsonStr);
        return new ParsedItem(jsonStr, ctx, externalIdOf(ctx, uniqueKey, spec));
    }

    /** An item's promoted cells, read and converted to their column types; {@code unreadable} of them did not read. */
    private record Cells(Object[] values, int unreadable) {}

    private Cells cellsOf(HybridJsonbSpec spec, List<JsonPath> colPaths, ParsedItem item, Map<String, Object> cells) {
        List<PromotedColumn> promote = spec.promote();
        Object[] values = new Object[promote.size()];
        int unreadable = 0;
        for (int ci = 0; ci < promote.size(); ci++) {
            PromotedColumn col = promote.get(ci);
            // A mapping's row is already read and transformed.
            Object raw = cells != null ? cells.get(col.name()) : pathValue(item.ctx(), colPaths.get(ci), col);
            Object val = converted(raw, col.type());
            if (val == UNREADABLE) {
                unreadable++;
                val = null;   // the row is written; its payload keeps the value whole in `data`
            }
            values[ci] = val;
        }
        return new Cells(values, unreadable);
    }

    /** One row's parameters, from the 1-based position {@code base + 1}. */
    private void bindRow(PreparedStatement ps, int base, HybridJsonbSpec spec, ParsedItem item, Object[] values,
                         String rowHash, long tenantId, UUID connectorId, UUID endpointId) throws SQLException {
        int i = base + 1;
        ps.setLong(i++, tenantId);
        ps.setString(i++, item.externalId());
        List<PromotedColumn> promote = spec.promote();
        for (int ci = 0; ci < promote.size(); ci++) bindTyped(ps, i++, values[ci], promote.get(ci).type());
        ps.setString(i++, rowHash);
        ps.setString(i++, item.json());  // reuse already-serialized JSON
        ps.setObject(i++, connectorId);
        ps.setObject(i,   endpointId);
    }

    private int bind(PreparedStatement ps, HybridJsonbSpec spec, List<JsonPath> colPaths,
                      ParsedItem item, Map<String, Object> cells, String rowHash,
                      long tenantId, UUID connectorId, UUID endpointId) throws SQLException {
        Cells row = cellsOf(spec, colPaths, item, cells);
        bindRow(ps, 0, spec, item, row.values(), rowHash, tenantId, connectorId, endpointId);
        return row.unreadable();
    }

    private static String externalIdOf(ReadContext ctx, JsonPath uniqueKey, HybridJsonbSpec spec) {
        String externalId = keyOf(ctx.read(uniqueKey, Object.class), spec);
        // Named here, before the NOT NULL column refuses it with a SQLSTATE whose message is withheld (#1630).
        if (externalId == null) {
            throw new ConnectorException("unique_key '" + spec.uniqueKeyPath() + "' reads no value on an item of "
                + spec.table() + ": every item needs a key");
        }
        return externalId;
    }

    /** The row's {@code external_id}: the value at the key's path through its transform; {@code null} when none. */
    static String keyOf(Object rawId, HybridJsonbSpec spec) {
        Object key = applyTransform(rawId, spec.uniqueKeyTransform(), "unique_key");
        return key == null ? null : key.toString();
    }

    /** The transform's result, or {@link #UNREADABLE} when the value is not one it reads — one cell, not the run. */
    private static Object transformed(Object raw, PromotedColumn col) {
        try {
            return applyTransform(raw, col.transform(), col.name());
        } catch (IllegalArgumentException | ClassCastException | DateTimeException notReadable) {
            return UNREADABLE;
        }
    }

    /**
     * A value as text. A list or an object is JSON written the way the hub writes it everywhere else: the JSON-path
     * library's own {@code toString} escapes every {@code /} as {@code \/} (#1867), so a multi-value field read
     * differently from a single-value one.
     */
    private static String textOf(Object val) {
        if (!(val instanceof List<?>) && !(val instanceof Map<?, ?>)) return String.valueOf(val);
        try {
            return JSON.writeValueAsString(val);
        } catch (Exception unwritable) {
            throw new IllegalStateException("HybridJsonbWriter: JSON serialization failed", unwritable);
        }
    }

    /** A plain decimal numeral, bounded: never an exponent to expand (#1090). */
    private static final Pattern DECIMAL_NUMERAL = Pattern.compile("-?\\d{1,38}(\\.\\d{1,38})?");

    /** A promoted value that does not read as its column's type: bound NULL, counted as a cell error (#1398). */
    public static final Object UNREADABLE = new Object();

    /**
     * The value as its declared column type, or {@link #UNREADABLE} (#1398). A JSON API sends instants — and
     * often numbers — as strings; bound as they came, they reached PostgreSQL as varchar and failed the whole run
     * on a type error. A string is read as the type says: an instant as {@link TemporalText} reads one (the rule
     * {@code parse_iso8601} and the view filters share), a numeral as a number, {@code true}/{@code false} as a
     * boolean. What does not read is one cell, not the run.
     */
    public static Object converted(Object val, SqlType type) {
        if (val == null || val == UNREADABLE) return val;
        try {
            return switch (type) {
                case TEXT -> textOf(val);
                // A string is parsed as a plain numeral only: "1e999999999" would otherwise be expanded (#1090).
                case BIGINT -> val instanceof Number n ? (Object) new BigDecimal(n.toString()).longValueExact()
                    : (Object) Long.parseLong(String.valueOf(val).strip());
                case INTEGER -> val instanceof Number n ? (Object) new BigDecimal(n.toString()).intValueExact()
                    : (Object) Integer.parseInt(String.valueOf(val).strip());
                case DECIMAL -> val instanceof BigDecimal b ? b : val instanceof Number n ? new BigDecimal(n.toString())
                    : DECIMAL_NUMERAL.matcher(String.valueOf(val).strip()).matches() ? new BigDecimal(String.valueOf(val).strip())
                    : UNREADABLE;
                case BOOLEAN -> val instanceof Boolean b ? b : booleanOf(String.valueOf(val));
                case TIMESTAMPTZ -> switch (val) {
                    case String text -> timestampOf(text);
                    case OffsetDateTime t -> t;
                    case Instant t -> t;
                    case ZonedDateTime t -> t;
                    case LocalDateTime t -> t;
                    // A mapping's hook hands back Java dates (#1617): a date alone is midnight UTC, as parse_iso8601 reads it.
                    case LocalDate d -> d.atStartOfDay().atOffset(ZoneOffset.UTC);
                    default -> UNREADABLE;
                };
                case DATE -> switch (val) {
                    case String text -> dateOf(text);
                    case LocalDate d -> d;
                    case OffsetDateTime t -> t.toLocalDate();
                    case LocalDateTime t -> t.toLocalDate();
                    case ZonedDateTime t -> t.withZoneSameInstant(ZoneOffset.UTC).toLocalDate();
                    case Instant t -> t.atOffset(ZoneOffset.UTC).toLocalDate();
                    default -> UNREADABLE;
                };
                case JSONB -> val;
            };
        } catch (ArithmeticException | IllegalArgumentException | DateTimeException notThatType) {
            return UNREADABLE;
        }
    }

    private static Object booleanOf(String text) {
        return switch (text.strip().toLowerCase(Locale.ROOT)) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> UNREADABLE;
        };
    }

    private static Object dateOf(String text) {
        Instant at = TemporalText.parse(text);
        return at == null ? UNREADABLE : at.atOffset(ZoneOffset.UTC).toLocalDate();
    }

    private static Object timestampOf(String text) {
        Instant at = TemporalText.parse(text);
        return at == null ? UNREADABLE : at.atOffset(ZoneOffset.UTC);
    }

    // package-private static: a pure function of (raw, transform), so a unit test can drive it
    // under a forced locale without standing up the writer.
    static Object applyTransform(Object raw, Optional<Transform> t, String column) {
        if (raw == null || t.isEmpty()) return raw;
        return switch (t.get()) {
            case FROM_EPOCH    -> OffsetDateTime.ofInstant(
                                    Instant.ofEpochSecond(((Number) raw).longValue()), ZoneOffset.UTC);
            case PARSE_ISO8601 -> {
                // Not OffsetDateTime.parse: that demands an offset, and the single most common ERP
                // export — "2008-04-18T00:00:00" — has none, so a valid ISO-8601 local date-time
                // failed the whole run. TemporalText is the rule the view filter reads the same
                // column with; sharing it is what keeps a filter typed against a value on screen
                // matching the row ingestion wrote.
                Instant at = TemporalText.parse(String.valueOf(raw));
                if (at == null) throw new IllegalArgumentException(
                    "column '" + column + "': parse_iso8601 cannot read '" + raw
                    + "' as a timestamp — expected an ISO-8601 date, date-time, or offset date-time");
                yield at.atOffset(ZoneOffset.UTC);
            }
            case TO_LOWER      -> String.valueOf(raw).toLowerCase(Locale.ROOT);
            case TO_UPPER      -> String.valueOf(raw).toUpperCase(Locale.ROOT);
            case TRIM          -> String.valueOf(raw).trim();
            case PARSE_QUANTITY -> Quantity.parse(String.valueOf(raw));
            case LAST_PATH_SEGMENT -> lastPathSegment(raw);
            case FROM_ODATA_DATE -> fromODataDate(raw, column);
            case TO_JSON_STRING -> {
                try { yield JSON.writeValueAsString(raw); }
                catch (Exception e) { throw new RuntimeException("TO_JSON_STRING failed", e); }
            }
        };
    }

    /**
     * {@code /Date(<ms>)/}, with an optional {@code ±<minutes>} (a DateTimeOffset): the milliseconds are the UTC
     * instant, the offset the zone the source showed it in, so it is checked and not applied (#1826).
     */
    private static final Pattern ODATA_DATE = Pattern.compile("/Date\\((-?\\d{1,15})(?:[+-](\\d{1,4}))?\\)/");

    /** The first and last millisecond a timestamptz written from a four-digit year can hold: 0001-01-01 … 9999-12-31. */
    private static final long ODATA_MIN_MS = -62_135_596_800_000L;
    private static final long ODATA_MAX_MS = 253_402_300_799_999L;
    private static final int ODATA_MAX_OFFSET_MINUTES = 1_440;

    private static OffsetDateTime fromODataDate(Object raw, String column) {
        Matcher m = raw instanceof CharSequence text ? ODATA_DATE.matcher(text.toString().strip()) : null;
        if (m == null || !m.matches()) throw unreadableODataDate(raw, column);
        long ms = Long.parseLong(m.group(1));
        boolean offsetInRange = m.group(2) == null || Integer.parseInt(m.group(2)) <= ODATA_MAX_OFFSET_MINUTES;
        if (ms < ODATA_MIN_MS || ms > ODATA_MAX_MS || !offsetInRange) throw unreadableODataDate(raw, column);
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC);
    }

    private static IllegalArgumentException unreadableODataDate(Object raw, String column) {
        // The value is the source's data and may be a key the run fails on: say its type, not its content.
        return new IllegalArgumentException("column '" + column + "': from_odata_date cannot read a "
            + raw.getClass().getSimpleName() + " value — expected /Date(<ms>)/ or /Date(<ms>±<minutes>)/, minutes at most "
            + ODATA_MAX_OFFSET_MINUTES + ", in years 0001 to 9999");
    }

    /**
     * The last non-empty segment of a link's path, percent-decoded after the split ({@code SKU%2F7} is one segment);
     * {@code null} for a value that is not text, not a URI, or has no segment — never an empty key.
     */
    private static String lastPathSegment(Object raw) {
        if (!(raw instanceof CharSequence text)) return null;
        try {
            String path = new URI(text.toString().strip()).getRawPath();
            String[] segments = path == null ? new String[0] : path.split("/");
            for (int i = segments.length - 1; i >= 0; i--) {
                if (!segments[i].isEmpty()) return new URI("/" + segments[i]).getPath().substring(1);
            }
            return null;
        } catch (URISyntaxException notALink) {
            return null;
        }
    }

    private void bindTyped(PreparedStatement ps, int idx, Object val, SqlType type) throws SQLException {
        if (val == null) { ps.setNull(idx, Types.NULL); return; }
        switch (type) {
            case TEXT        -> ps.setString(idx, textOf(val));
            case BIGINT      -> ps.setLong(idx, ((Number) val).longValue());
            case INTEGER     -> ps.setInt(idx, ((Number) val).intValue());
            case BOOLEAN     -> ps.setBoolean(idx, (Boolean) val);
            case DECIMAL     -> ps.setBigDecimal(idx,
                                    val instanceof BigDecimal b ? b : new BigDecimal(val.toString()));
            case TIMESTAMPTZ -> ps.setObject(idx, DynamicTableWriter.bindable(val, "TIMESTAMPTZ"));
            case DATE        -> ps.setObject(idx, val);
            case JSONB       -> {
                try { ps.setObject(idx, JSON.writeValueAsString(val), Types.OTHER); }
                catch (Exception e) { throw new SQLException("JSONB bind failed", e); }
            }
        }
    }

    private String indexName(String tableName, String suffix) {
        return "idx_" + tableName.replace('.', '_') + "_" + suffix;
    }

    // ── Identifier quoting ───────────────────────────────────────────────────

    /** A YAML-configured table/column name must be present before it is quoted. */
    private static String qi(String ident) {
        if (Text.blank(ident)) throw new IllegalArgumentException("identifier required");
        return quoteIdent(ident);
    }

    /**
     * Returns a fully-qualified, quoted table reference. Handles {@code schema.table} or plain
     * {@code table}. A leading or trailing dot means one segment is empty — {@code "schema."}
     * would otherwise quote into {@code "schema".""} — so it is refused up front.
     * Package-visible so the guard can be pinned without a live datasource.
     */
    static String fqn(String fqTable) {
        if (Text.blank(fqTable)) throw new IllegalArgumentException("identifier required");
        if (fqTable.startsWith(".") || fqTable.endsWith(".")) {
            throw new IllegalArgumentException("blank identifier segment");
        }
        return quoteTable(fqTable);
    }

    /**
     * Forget one table's cached columns + registration marker — called when the table is dropped (connector delete /
     * purge), so the next write takes {@link #ensureTable}'s checked path and makes the table again, instead of inserting
     * into a relation that no longer exists. A write that fails on a missing table or column forgets it too.
     */
    public void forget(String table) {
        if (table == null) return;
        schemaCache.remove(table);
        // registered keys are always "<table>|<tenantId>|<connectorId>" (registerIfNeeded), so match by that prefix.
        registered.removeIf(k -> k.startsWith(table + "|"));
    }

    /**
     * Test-only hook: drops the in-memory schema cache, so the next write of each table takes {@link #ensureTable}'s
     * checked path, as a restarted process does. Do NOT call at runtime.
     */
    void clearSchemaCache() {
        schemaCache.clear();
        registered.clear();
    }
}
