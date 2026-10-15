package io.lumnik.hub.connector.mapping.writer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.ReadContext;
import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.PromotedColumn;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.SqlType;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.Transform;
import io.lumnik.hub.sql.TemporalText;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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

    /** In-memory cache of known columns per table — cleared on drift or by tests. */
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
    public void write(HybridJsonbSpec spec, List<Map<String, Object>> items,
                      long tenantId, UUID connectorId, UUID endpointId) {
        write(spec, items, null, tenantId, connectorId, endpointId);
    }

    /**
     * Full write: {@code systemContexts} (aligned with {@code items}) supply {@code _row_hash} —
     * the row identity the RAG/table targets already persist and this branch used to throw away
     * (C4/P18 PR1). {@code null} contexts bind NULL hashes (direct/test callers).
     */
    public void write(HybridJsonbSpec spec, List<Map<String, Object>> items,
                      List<Map<String, Object>> systemContexts,
                      long tenantId, UUID connectorId, UUID endpointId) {
        if (items.isEmpty()) return;
        // Pre-compile JsonPath expressions once per write call to avoid recompilation per row
        JsonPath uniqueKey = JsonPath.compile(spec.uniqueKeyPath());
        List<JsonPath> colPaths = spec.promote().stream()
            .map(p -> JsonPath.compile(p.jsonPath()))
            .toList();
        try (Connection c = adminDs.getConnection()) {
            boolean schemaChanged = ensureTable(c, spec);
            if (schemaChanged) registered.remove(spec.table() + "|" + tenantId);
            registerIfNeeded(spec, tenantId, connectorId);
            String sql = buildInsertSql(spec);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int r = 0; r < items.size(); r++) {
                    String rowHash = systemContexts == null ? null
                        : (String) systemContexts.get(r).get("_row_hash");
                    bind(ps, spec, uniqueKey, colPaths, items.get(r), rowHash,
                         tenantId, connectorId, endpointId);
                }
                ps.executeBatch();
            }
        } catch (SQLException e) {
            throw new RuntimeException("HybridJsonbWriter failed for " + spec.table(), e);
        }
    }

    /**
     * Register the ext.* table in {@code connector.dynamic_table} so scope discovery sees it —
     * the missing link that kept every hybrid scope un-answerable (HybridTargetSchemaCardIT).
     * Key {@code hybrid:<table>}: unique per endpoint target, and immune to the
     * UNIQUE(tenant_id, mapping_id) trap — two endpoints of one mapping write two tables and
     * get two registry rows. For connector runs the card appears when the completed RUN
     * triggers rediscover; the run-less one-shot CSV path rediscoveres its own scopes after
     * its ingest commits (spec 2026-09-02) — one command, an askable table.
     */
    private void registerIfNeeded(HybridJsonbSpec spec, long tenantId, UUID connectorId) {
        String key = spec.table() + "|" + tenantId;
        if (!registered.add(key)) return;
        String[] parts = spec.table().split("\\.", 2);
        String schema = parts.length == 2 ? parts[0] : "public";
        String table  = parts.length == 2 ? parts[1] : parts[0];
        // Business columns ONLY — every entry here becomes a queryable column on the scope's
        // schema card, surfaced to the LLM. Lineage (row_hash, tenant_id, endpoint_id…) stays
        // out, same contract as DynamicTableWriter registering just the inferred columns.
        Map<String, String> cols = new LinkedHashMap<>();
        cols.put("external_id", "TEXT");
        for (PromotedColumn col : spec.promote()) cols.put(col.name(), col.type().ddl);
        cols.put("data", "JSONB");
        try {
            registry.register(tenantId, DynamicTableRegistry.HYBRID_MAPPING_PREFIX + spec.table(),
          schema, table, cols, connectorId);
        } catch (RuntimeException e) {
            // Registration must never fail a data write. The known case: the one-shot CSV path
            // writes rows INSIDE the transaction that creates its connector row, so the registry
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
                            registerIfNeeded(spec, tenantId, connectorId);
                        }
                    }
                });
            }
            Logger.getLogger(HybridJsonbWriter.class)
                .infof("ext table %s not registered for discovery (will retry on next write): %s",
                       spec.table(), e.getMessage());
        }
    }

    // ── Schema management ────────────────────────────────────────────────────

    /** @return true when the table was created or any column was added — the caller then
     *  refreshes the registry entry so the schema card follows the drift (DynamicTableWriter
     *  re-registers on evolve; same contract here). */
    private boolean ensureTable(Connection c, HybridJsonbSpec spec) throws SQLException {
        // Invariant: schemaCache entries are always fully populated after createTable;
        // IF NOT EXISTS guards in createTable/addColumn make this safe under concurrent callers.
        Set<String> known = schemaCache.computeIfAbsent(spec.table(), t -> loadColumns(c, t));
        if (known.isEmpty()) {
            createTable(c, spec);
            schemaCache.put(spec.table(), loadColumns(c, spec.table()));
            return true;
        }
        boolean changed = false;
        // Drift: add any promoted columns not yet in the table
        for (PromotedColumn col : spec.promote()) {
            if (!known.contains(col.name())) {
                addColumn(c, spec.table(), col);
                known.add(col.name());
                changed = true;
            }
        }
        // Retrofit: tables created before C4/P18 PR1 lack row_hash
        if (!known.contains(ROW_HASH)) {
            try (Statement st = c.createStatement()) {
                st.execute("ALTER TABLE " + fqn(spec.table()) + " ADD COLUMN IF NOT EXISTS " + ROW_HASH + " TEXT");
            }
            known.add(ROW_HASH);
            changed = true;
        }
        return changed;
    }

    private Set<String> loadColumns(Connection c, String fqTable) {
        String[] parts = fqTable.split("\\.", 2);
        String schema = parts.length == 2 ? parts[0] : "public";
        String table  = parts.length == 2 ? parts[1] : parts[0];
        Set<String> cols = new HashSet<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ?")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) cols.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("loadColumns failed for " + fqTable, e);
        }
        return cols;
    }

    /** Package-visible so the emitted DDL can be pinned without a live datasource. */
    void createTable(Connection c, HybridJsonbSpec spec) throws SQLException {
        String[] parts = spec.table().split("\\.", 2);
        String schemaName = parts.length == 2 ? parts[0] : null;
        String tableName  = parts.length == 2 ? parts[1] : parts[0];
        String qfqTable   = fqn(spec.table());
        try (Statement s = c.createStatement()) {
            if (schemaName != null) {
                s.execute("CREATE SCHEMA IF NOT EXISTS " + qi(schemaName));
                // Schema USAGE grant: the ext.* schema is created here by the admin (owner) datasource
                // and is NOT covered by any migration's USAGE grant — the connector schema is granted
                // via V9, ext is not (auto-memory schema-usage-grant-gap). Without USAGE the non-owner
                // lumnik_app cannot reference any object inside ext at all ("permission denied for
                // schema ext") on an RLS-enforced hub. No-op when owner = same user (test mode).
                // Routed through RlsDdl.guarded (not hand-rolled here) so this DO block shares RlsDdl's
                // single dollar-quote tag and gets the same DDL-injection protection as every other
                // grant, instead of carrying its own bare-$$ copy of that logic.
                // One grant, one block: EXCEPTION WHEN OTHERS aborts its block, so a pair sharing
                // one would let a missing lumnik_app silently cost lumnik_readonly its USAGE — and
                // without USAGE on the schema, ask-to-SQL cannot reach the table whatever its own
                // grants say.
                s.execute(RlsDdl.guarded(RlsDdl.grantSchemaUsageApp(schemaName)));
                s.execute(RlsDdl.guarded(RlsDdl.grantSchemaUsageReadonly(schemaName)));
            }

            StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ")
                .append(qfqTable).append(" (")
                .append("id BIGSERIAL PRIMARY KEY, ")
                .append("tenant_id BIGINT NOT NULL, ")
                .append("external_id TEXT NOT NULL, ");
            for (PromotedColumn col : spec.promote()) {
                sb.append(qi(col.name())).append(' ').append(col.type().ddl).append(", ");
            }
            sb.append("row_hash TEXT, ")
              .append("data JSONB NOT NULL, ")
              .append("indexed_at TIMESTAMPTZ DEFAULT now(), ")
              .append("connector_id UUID NOT NULL, ")
              .append("endpoint_id UUID NOT NULL, ")
              .append("UNIQUE (tenant_id, external_id))");
            s.execute(sb.toString());

            String idxName = indexName(tableName, "data_gin");
            s.execute("CREATE INDEX IF NOT EXISTS " + qi(idxName) +
                      " ON " + qfqTable + " USING GIN (data jsonb_path_ops)");
            // Tenant isolation: the same policy the connector.t_* tables get, from RlsDdl.
            s.execute(RlsDdl.enableRowLevelSecurity(schemaName, tableName));
            s.execute(RlsDdl.tenantIsolationPolicy(schemaName, tableName));
            // One grant per guarded block, the DynamicTableWriter shape. Grouped, the readonly
            // SELECT sat second behind the app's DML: a failing first statement aborted the block
            // and took it with it, swallowed, leaving a table the schema card advertises as
            // askable and the ask role cannot read.
            s.execute(RlsDdl.guarded(RlsDdl.grantAppDml(schemaName, tableName)));
            s.execute(RlsDdl.guarded(RlsDdl.grantReadonlySelect(schemaName, tableName)));
            s.execute(RlsDdl.guarded(RlsDdl.grantSequenceUsage(schemaName, tableName + "_id_seq")));
        }
    }

    private void addColumn(Connection c, String table, PromotedColumn col) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("ALTER TABLE " + fqn(table) + " ADD COLUMN IF NOT EXISTS " + qi(col.name()) + " " + col.type().ddl);
        }
    }

    // ── SQL building ─────────────────────────────────────────────────────────

    private String buildInsertSql(HybridJsonbSpec spec) {
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
               " VALUES (" + String.join(", ", qs) + ")" +
               " ON CONFLICT (tenant_id, external_id) DO UPDATE SET " + set +
               ", data = EXCLUDED.data, indexed_at = now()" +
               " WHERE EXCLUDED." + ROW_HASH + " IS NULL" +
               " OR (" + stored + ") IS DISTINCT FROM (" + incoming + ")";
    }

    // ── Row binding ──────────────────────────────────────────────────────────

    private void bind(PreparedStatement ps, HybridJsonbSpec spec,
                      JsonPath uniqueKey, List<JsonPath> colPaths,
                      Map<String, Object> item, String rowHash,
                      long tenantId, UUID connectorId, UUID endpointId) throws SQLException {
        // Serialize to JSON string first so JsonPath operates on a well-known input type
        String jsonStr;
        try { jsonStr = JSON.writeValueAsString(item); }
        catch (Exception e) { throw new SQLException("HybridJsonbWriter: JSON serialization failed", e); }
        ReadContext ctx = JsonPath.using(JP).parse(jsonStr);
        Object rawId = ctx.read(uniqueKey, Object.class);
        String externalId = (rawId == null) ? null : rawId.toString();

        int i = 1;
        ps.setLong(i++, tenantId);
        ps.setString(i++, externalId);

        List<PromotedColumn> promote = spec.promote();
        for (int ci = 0; ci < promote.size(); ci++) {
            PromotedColumn col = promote.get(ci);
            Object raw = ctx.read(colPaths.get(ci), Object.class);
            Object val = applyTransform(raw, col.transform(), col.name());
            bindTyped(ps, i++, val, col.type());
        }

        ps.setString(i++, rowHash);
        ps.setString(i++, jsonStr);  // reuse already-serialized JSON
        ps.setObject(i++, connectorId);
        ps.setObject(i,   endpointId);
        ps.addBatch();
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
            case TO_JSON_STRING -> {
                try { yield JSON.writeValueAsString(raw); }
                catch (Exception e) { throw new RuntimeException("TO_JSON_STRING failed", e); }
            }
        };
    }

    private void bindTyped(PreparedStatement ps, int idx, Object val, SqlType type) throws SQLException {
        if (val == null) { ps.setNull(idx, Types.NULL); return; }
        switch (type) {
            case TEXT        -> ps.setString(idx, String.valueOf(val));
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
        if (ident == null || ident.isBlank()) throw new IllegalArgumentException("identifier required");
        return quoteIdent(ident);
    }

    /**
     * Returns a fully-qualified, quoted table reference. Handles {@code schema.table} or plain
     * {@code table}. A leading or trailing dot means one segment is empty — {@code "schema."}
     * would otherwise quote into {@code "schema".""} — so it is refused up front.
     * Package-visible so the guard can be pinned without a live datasource.
     */
    static String fqn(String fqTable) {
        if (fqTable == null || fqTable.isBlank()) throw new IllegalArgumentException("identifier required");
        if (fqTable.startsWith(".") || fqTable.endsWith(".")) {
            throw new IllegalArgumentException("blank identifier segment");
        }
        return quoteTable(fqTable);
    }

    /**
     * Forget one table's cached schema + registration marker — called when the table is dropped
     * (connector delete / purge) so a re-ingest under the same name re-reads an empty
     * information_schema and recreates the table, instead of trusting a stale cache, skipping
     * {@code CREATE TABLE}, and inserting into a relation that no longer exists.
     */
    public void forget(String table) {
        if (table == null) return;
        schemaCache.remove(table);
        // registered keys are always "<table>|<tenantId>" (registerIfNeeded), so match by that prefix.
        registered.removeIf(k -> k.startsWith(table + "|"));
    }

    /**
     * Test-only hook: drops the in-memory schema cache so the next write re-reads from information_schema.
     * Do NOT call at runtime.
     */
    void clearSchemaCache() {
        schemaCache.clear();
        registered.clear();
    }
}
