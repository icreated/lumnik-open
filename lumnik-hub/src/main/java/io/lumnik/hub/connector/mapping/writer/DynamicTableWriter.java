package io.lumnik.hub.connector.mapping.writer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.event.RunTableStats;
import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import io.lumnik.hub.event.RowEventGate;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static io.lumnik.hub.sql.SqlIdentifiers.quoteIdent;

/**
 * Writes rows to per-tenant dynamic Postgres tables.
 * Auto-creates the table (with RLS + indexes) on first write.
 *
 * <p>Uses the admin datasource for all DDL and DML so that:
 * <ul>
 *   <li>DDL executes without RLS restrictions.
 *   <li>Inserts bypass RLS (admin BYPASSRLS in production; same user in tests).
 * </ul>
 *
 * <p>Each table carries a {@code tenant_id} column so RLS can isolate per-tenant rows
 * when accessed via the default (app) datasource.
 */
@ApplicationScoped
public class DynamicTableWriter {

  @Inject SchemaInferrer inferrer;
  @Inject DynamicTableRegistry registry;
  @Inject RunTableStats stats;
  @Inject RowEventGate gate;
  @Inject ObjectMapper mapper;

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  /**
   * Write a batch of rows to the target table.
   * Creates the table on first call for this (tenantId, mappingId) pair.
   *
   * @param spec   table spec from mapping configuration
   * @param rows   data rows (column → value), one map per row
   * @param sys    system context maps (one per row): _row_hash, _source, _run_id, _mapping_version, _mapping
   * @param tenantId current tenant
   */
  /** Overload without a connector link (internal/test paths). */
  public void write(MappingTarget.Table spec, List<Map<String, Object>> rows,
                    List<Map<String, Object>> systemContexts, Long tenantId) {
    write(spec, rows, systemContexts, tenantId, null);
  }

  public void write(MappingTarget.Table spec, List<Map<String, Object>> rows,
                    List<Map<String, Object>> systemContexts, Long tenantId,
                    UUID connectorId) {
    write(spec, rows, systemContexts, tenantId, connectorId, Map.of());
  }

  /**
   * @param sourceTypeHints column → PostgreSQL type from the source's declared metadata
   *   (e.g. JDBC {@code ResultSetMetaData}). Used by {@link SchemaInferrer} only for columns the
   *   batch could not type because every sampled value was NULL — fixing sparse columns that would
   *   otherwise freeze to TEXT and break cross-table joins. Pass {@code Map.of()} when unavailable.
   */
  public void write(MappingTarget.Table spec, List<Map<String, Object>> rows,
                    List<Map<String, Object>> systemContexts, Long tenantId,
                    UUID connectorId, Map<String, String> sourceTypeHints) {
    if (rows.isEmpty()) return;
    refuseSystemColumnCollisions(rows);

    String mappingId = (String) systemContexts.get(0).get("_mapping");

    Map<String, String> registered = registry.findSchema(tenantId, mappingId);
    registered = registered == null
        ? createSchema(spec, rows, sourceTypeHints, tenantId, mappingId, connectorId)
        : evolveSchema(spec, rows, registered, tenantId, mappingId, connectorId);

    insertRows(spec, rows, systemContexts, tenantId, registered, connectorId);
  }

  /** First write for this mapping: infer the schema, create the table + indexes + RLS, register it. */
  private Map<String, String> createSchema(MappingTarget.Table spec, List<Map<String, Object>> rows,
                                           Map<String, String> sourceTypeHints, Long tenantId,
                                           String mappingId, UUID connectorId) {
    Map<String, String> inferred = inferrer.infer(rows, sourceTypeHints);
    createTable(spec, inferred);
    createIndexes(spec);
    enableRLS(spec);
    registry.register(tenantId, mappingId, spec.schema(), spec.name(), inferred, connectorId);
    return inferred;
  }

  /** Subsequent writes: ALTER TABLE for any columns not seen before, then re-register the schema. */
  private Map<String, String> evolveSchema(MappingTarget.Table spec, List<Map<String, Object>> rows,
                                           Map<String, String> registered, Long tenantId,
                                           String mappingId, UUID connectorId) {
    Set<String> newCols = detectNewColumns(rows, registered);
    if (newCols.isEmpty()) return registered;

    Map<String, String> updated = new HashMap<>(registered);
    for (String col : newCols) {
      alterAddColumn(spec, col, "TEXT");
      updated.put(col, "TEXT");
    }
    // Run all DDL BEFORE advancing the registry — mirrors the creation branch, where register()
    // is the last step (the "commit" of successful DDL). If createIndexes throws, the registry
    // stays in its pre-evolution state so the next run re-detects newCols and retries; ALTER and
    // index DDL are both IF NOT EXISTS, so the retry is safe.
    createIndexes(spec);
    registry.register(tenantId, mappingId, spec.schema(), spec.name(), updated, connectorId);
    return updated;
  }

  private Set<String> detectNewColumns(List<Map<String, Object>> rows, Map<String, String> registered) {
    Set<String> newCols = new HashSet<>();
    for (var r : rows) {
      for (String k : r.keySet()) {
        if (!registered.containsKey(k)) newCols.add(k);
      }
    }
    return newCols;
  }

  private void alterAddColumn(MappingTarget.Table spec, String column, String pgType) {
    String fqn = quoteIdent(spec.schema()) + '.' + quoteIdent(spec.name());
    exec("ALTER TABLE " + fqn + " ADD COLUMN IF NOT EXISTS " + quoteIdent(column) + " " + pgType);
  }

  // ── DDL ────────────────────────────────────────────────────────────────────

  // ── System column names ────────────────────────────────────────────────────
  // Declared BEFORE SYSTEM_COLUMNS on purpose: static initialisers run in source order, and
  // Set.of() rejects null, so a name declared after the set it feeds would fail at class load.

  private static final String ID = "id";
  /** Content-hash system column — the per-row dedup key written by MultiTargetWriter. */
  private static final String ROW_HASH = "_row_hash";
  private static final String INGESTED_AT = "_ingested_at";
  /** The originating connector, or "ingest" when none is known. */
  private static final String SOURCE = "_source";
  /** Ingestion-run system column carrying the originating run id. */
  private static final String RUN_ID = "_run_id";
  private static final String MAPPING_VERSION = "_mapping_version";
  private static final String TENANT_ID = "tenant_id";

  /**
   * The columns {@link #createTableDdl} always creates. A payload column carrying one of these
   * names cannot be written: on a fresh table Postgres refuses the duplicate, and on an existing
   * one {@code ADD COLUMN IF NOT EXISTS} would silently no-op and drop the values from then on.
   * Pinned against the DDL by {@code DynamicTableSystemColumnsTest}.
   */
  public static final Set<String> SYSTEM_COLUMNS =
      Set.of(ID, ROW_HASH, INGESTED_AT, SOURCE, RUN_ID, MAPPING_VERSION, TENANT_ID);

  /**
   * Refuses payload columns that collide with {@link #SYSTEM_COLUMNS}. Static + package-visible so
   * the refusal can be pinned without a datasource.
   *
   * <p>The remedy names each connector that reaches this writer, because the gesture differs and
   * one of them has none: jdbc declares {@code rename}, csv reads row hooks from its manifest
   * ({@code CsvFileConnectorType.buildSpec}), but {@code KafkaConnectorType.dumpSpec} hardcodes
   * empty hook tiers — a kafka field must be renamed upstream, in the payload the topic carries.
   * Promising a map-columns hook to every connector would send that installer after a gesture that
   * does not exist.
   */
  static void refuseSystemColumnCollisions(List<Map<String, Object>> rows) {
    for (Map<String, Object> row : rows) {
      for (String col : row.keySet()) {
        if (SYSTEM_COLUMNS.contains(col)) {
          throw new ConfigValidationException(
              "source column '" + col + "' collides with a hub system column "
              + "(" + String.join(", ", new TreeSet<>(SYSTEM_COLUMNS)) + "); "
              + "it must reach the hub under another name — "
              + "jdbc: source.tables[].rename; "
              + "csv: a map-columns row hook under spec.mapping; "
              + "kafka: no rename exists, the field must be renamed in the topic payload");
        }
      }
    }
  }

  private void createTable(MappingTarget.Table spec, Map<String, String> schema) {
    exec("CREATE SCHEMA IF NOT EXISTS " + quoteIdent(spec.schema()));
    exec(createTableDdl(spec, schema));
  }

  /**
   * The {@code CREATE TABLE} body: the fixed system prelude ({@link #SYSTEM_COLUMNS}) followed by
   * every inferred payload column. Package-visible and pure so it can be pinned in a test without a
   * datasource.
   */
  String createTableDdl(MappingTarget.Table spec, Map<String, String> schema) {
    StringBuilder t = new StringBuilder();
    t.append("CREATE TABLE IF NOT EXISTS ")
     .append(fqn(spec))
     .append(" (")
     .append("id BIGSERIAL PRIMARY KEY, ")
     .append("_row_hash TEXT UNIQUE NOT NULL, ")
     .append("_ingested_at TIMESTAMPTZ NOT NULL DEFAULT now(), ")
     .append("_source TEXT, ")
     .append("_run_id UUID, ")
     .append("_mapping_version INT, ")
     .append("tenant_id BIGINT NOT NULL");
    for (var e : schema.entrySet()) {
      t.append(", ").append(quoteIdent(e.getKey())).append(' ').append(e.getValue());
    }
    t.append(')');
    return t.toString();
  }

  private void createIndexes(MappingTarget.Table spec) {
    if (spec.indexes() == null || spec.indexes().isEmpty()) return;
    for (var idx : spec.indexes()) {
      StringBuilder s = new StringBuilder("CREATE ");
      if (idx.unique()) s.append("UNIQUE ");
      String idxName = spec.name() + "_idx_" + String.join("_", idx.columns());
      s.append("INDEX IF NOT EXISTS ").append(quoteIdent(idxName))
       .append(" ON ").append(fqn(spec)).append(" (");
      for (int i = 0; i < idx.columns().size(); i++) {
        if (i > 0) s.append(',');
        s.append(quoteIdent(idx.columns().get(i)));
      }
      s.append(')');
      exec(s.toString());
    }
  }

  /**
   * Stamps the shared tenant-isolation DDL ({@link RlsDdl}) on a freshly created table.
   * Package-visible so the emitted DDL can be pinned without a live datasource.
   */
  void enableRLS(MappingTarget.Table spec) {
    String schema = spec.schema();
    String table = spec.name();
    exec(RlsDdl.enableRowLevelSecurity(schema, table));
    exec(RlsDdl.tenantIsolationPolicy(schema, table));
    exec(RlsDdl.tenantIdIndex(schema, table));
    // One guarded block per grant: each stands or falls on its own, so a missing lumnik_app never
    // costs the table its lumnik_readonly grant.
    exec(RlsDdl.guarded(RlsDdl.grantAppDml(schema, table)));
    exec(RlsDdl.guarded(RlsDdl.grantReadonlySelect(schema, table)));
    exec(RlsDdl.guarded(RlsDdl.grantSequenceUsage(schema, table + "_id_seq")));
  }

  // ── DML ────────────────────────────────────────────────────────────────────

  /** Fixed system columns, in bind order, always first in the INSERT. */
  private static final List<String> FIXED_COLS =
      List.of(ROW_HASH, SOURCE, RUN_ID, MAPPING_VERSION, TENANT_ID);

  // Upper bound on rows per gated multi-row INSERT. The real per-statement size is clamped by
  // {@link #subBatchSize(int)} so that rows × colsPerRow never exceeds Postgres' 65535 bind-param
  // cap — wide ERP tables (>163 cols) would otherwise overflow it.
  private static final int EMIT_SUB_BATCH = 400;

  // Postgres / pgjdbc hard cap on bind parameters in one statement.
  private static final int MAX_BIND_PARAMS = 65535;

  /** Rows per gated sub-batch: {@link #EMIT_SUB_BATCH}, clamped so rows × colsPerRow ≤ 65535
   *  (always ≥ 1, even for absurdly wide tables). */
  static int subBatchSize(int colsPerRow) {
    return Math.clamp(MAX_BIND_PARAMS / colsPerRow, 1, EMIT_SUB_BATCH);
  }

  private static final String OUTBOX_SQL =
      "INSERT INTO core.event_outbox "
      + "(tenant_id, event_type, source, entity, entity_key, payload, occurred_at, created_at) "
      + "VALUES (?, ?, ?, ?, ?, ?::jsonb, NOW(), NOW())";

  /** Dispatcher: gated path emits per-new-row events in the data tx; un-gated path is the plain batch. */
  private void insertRows(MappingTarget.Table spec, List<Map<String, Object>> rows,
                          List<Map<String, Object>> sys, Long tenantId,
                          Map<String, String> schema, UUID connectorId) {
    if (rows.isEmpty()) return;
    if (gate.enabled(tenantId, fqnUnquoted(spec))) {
      insertRowsEmitting(spec, rows, sys, tenantId, schema, connectorId);
    } else {
      insertRowsBatched(spec, rows, sys, tenantId, schema);
    }
  }

  /** Un-gated path (default): pgjdbc batch + Task-1 written/skipped counts. Unchanged behavior. */
  private void insertRowsBatched(MappingTarget.Table spec, List<Map<String, Object>> rows,
                                 List<Map<String, Object>> sys, Long tenantId,
                                 Map<String, String> schema) {
    List<String> schemaCols = new ArrayList<>(schema.keySet());
    String sql = buildInsertSql(spec, schemaCols, conflictClause(spec), false, 1);

    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(sql)) {
      for (int r = 0; r < rows.size(); r++) {
        bindRow(ps, 0, rows.get(r), sys.get(r), tenantId, schemaCols);
        ps.addBatch();
      }
      int[] counts = ps.executeBatch();
      int written = 0;
      int skipped = 0;
      for (int n : counts) {
        // pgjdbc returns 1 for a real insert, 0 for an ON CONFLICT DO NOTHING no-op, and -2 (SUCCESS_NO_INFO) when it can't report a per-row count; treat anything != 0 as written, 0 as skipped.
        if (n != 0) written++; else skipped++;
      }
      stats.recordCounts(runIdOf(sys), fqnUnquoted(spec), written, skipped);
    } catch (Exception e) {
      throw new ConnectorException("DynamicTableWriter.insertRows failed: " + e.getMessage(), e);
    }
  }

  /**
   * Gated path: a multi-row {@code INSERT … RETURNING _row_hash, (xmax = 0)} detects genuinely-new
   * rows and writes one {@code data.row.changed} outbox event per insert — all on ONE admin
   * connection with a manual transaction, so data rows and their events commit atomically.
   *
   * <p>{@code xmax = 0} is true for a freshly inserted row, false for a DO UPDATE touch; DO NOTHING
   * conflicts return no row at all. So conflicts are event-silent and re-ingesting a file emits nothing.
   */
  // S1141: the nested try is the deliberate transaction shape — outer try-with-resources owns the
  // admin Connection, the inner one owns the outbox PreparedStatement and carries the rollback/
  // setAutoCommit(true) so data rows and events commit atomically. Extracting it would hand a helper
  // ~11 parameters (a worse S107), so the nesting stays.
  @SuppressWarnings("java:S1141")
  private void insertRowsEmitting(MappingTarget.Table spec, List<Map<String, Object>> rows,
                                  List<Map<String, Object>> sys, Long tenantId,
                                  Map<String, String> schema, UUID connectorId) {
    List<String> schemaCols = new ArrayList<>(schema.keySet());
    String fqn = fqnUnquoted(spec);
    String source = connectorId != null ? connectorId.toString() : "ingest";
    UUID runId = runIdOf(sys); // nullable: omitted from the event payload when absent

    // Fix A: de-dup the whole chunk by _row_hash (first occurrence wins) BEFORE building the
    // multi-row INSERT. Two identical rows share a content hash; with ON CONFLICT DO UPDATE,
    // Postgres rejects a statement that touches the same target row twice ("cannot affect row a
    // second time"). De-duping makes UPDATE/SKIP mode safe, keeps written/skipped truthful, and
    // emits one event per unique content. Done across the full chunk so dups straddling a sub-batch
    // boundary are caught too.
    //
    // ERROR mode: de-dup is intentionally skipped — an intra-chunk duplicate must hit the UNIQUE
    // constraint and fail, exactly as the un-gated path does. A subscription must never change
    // ingestion success/failure.
    boolean hasConflictClause = spec.onConflict() != MappingTarget.OnConflict.ERROR;
    List<Integer> idx = hasConflictClause ? dedupIndices(sys) : allIndices(rows.size());
    int droppedDups = rows.size() - idx.size();

    int colsPerRow = FIXED_COLS.size() + schemaCols.size();
    int sub = subBatchSize(colsPerRow);

    int written = 0;
    int returned = 0;
    try (Connection c = adminDs.getConnection()) {
      c.setAutoCommit(false);
      try (PreparedStatement outbox = c.prepareStatement(OUTBOX_SQL)) {
        for (int start = 0; start < idx.size(); start += sub) {
          int end = Math.min(start + sub, idx.size());
          SubBatchResult sb = writeSubBatch(c, outbox, spec, rows, sys, tenantId, schemaCols,
                                            idx, start, end, fqn, source, runId);
          written += sb.written();
          returned += sb.returned();
          // Flush the staged events per sub-batch so the outbox JDBC batch never grows past `sub`
          // rows — a 100k-row chunk would otherwise hold 100k addBatch() entries before one flush.
          // Still ONE transaction (commit once below): data + events stay atomic.
          outbox.executeBatch();
        }
        c.commit();
      } catch (Exception e) {
        c.rollback();
        throw e;
      } finally {
        c.setAutoCommit(true);
      }
    } catch (Exception e) {
      throw new ConnectorException("DynamicTableWriter.insertRows failed: " + e.getMessage(), e);
    }

    // skipped = dropped intra-chunk dups + cross-run conflicts (uniques that didn't come back).
    int skipped = droppedDups + (idx.size() - returned);
    stats.recordCounts(runIdOf(sys), fqn, written, skipped);
  }

  /** First-occurrence-wins ordered index list over the chunk, keyed by {@code _row_hash}. */
  private List<Integer> dedupIndices(List<Map<String, Object>> sys) {
    LinkedHashMap<String, Integer> firstByHash = new LinkedHashMap<>();
    for (int i = 0; i < sys.size(); i++) {
      String hash = (String) sys.get(i).get(ROW_HASH);
      firstByHash.putIfAbsent(hash, i);
    }
    return new ArrayList<>(firstByHash.values());
  }

  /** Identity index list (no de-dup): 0, 1, …, size-1. Used for ERROR mode. */
  private List<Integer> allIndices(int size) {
    List<Integer> idx = new ArrayList<>(size);
    for (int i = 0; i < size; i++) idx.add(i);
    return idx;
  }

  /** Per-sub-batch tallies: {@code written} = rows that came back (events for the inserted ones);
   *  {@code returned} = total rows the RETURNING gave (input − returned = skipped conflicts). */
  private record SubBatchResult(int written, int returned) {}

  /** Executes one multi-row INSERT … RETURNING over the de-duped indices {@code idx[start,end)}
   *  and stages the outbox events. */
  // S107: these are the row-binding coordinates for one INSERT … RETURNING sub-batch (statement,
  // spec, the parallel rows/sys lists, tenant, schema, the de-duped index window, run/source tags).
  // They travel together for one call; bundling them into a parameter object would only add an
  // allocation and indirection on the ingest hot path.
  @SuppressWarnings("java:S107")
  private SubBatchResult writeSubBatch(Connection c, PreparedStatement outbox, MappingTarget.Table spec,
                            List<Map<String, Object>> rows, List<Map<String, Object>> sys,
                            Long tenantId, List<String> schemaCols, List<Integer> idx,
                            int start, int end, String fqn, String source, UUID runId)
                            throws SQLException, JsonProcessingException {
    int n = end - start;
    String sql = buildInsertSql(spec, schemaCols, conflictClause(spec), true, n);
    int written = 0;
    int returned = 0;
    int colsPerRow = FIXED_COLS.size() + schemaCols.size();
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      for (int k = start; k < end; k++) {
        int r = idx.get(k);
        bindRow(ps, (k - start) * colsPerRow, rows.get(r), sys.get(r), tenantId, schemaCols);
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          returned++;
          String hash = rs.getString(1);
          boolean inserted = rs.getBoolean(2);
          // UPDATE touches (inserted=false) count as written too, to match the un-gated path.
          written++;
          if (inserted) {
            stageEvent(outbox, tenantId, source, fqn, hash, runId);
          }
        }
      }
    }
    return new SubBatchResult(written, returned);
  }

  private void stageEvent(PreparedStatement outbox, Long tenantId, String source,
                          String fqn, String hash, UUID runId) throws SQLException, JsonProcessingException {
    outbox.setLong(1, tenantId);
    outbox.setString(2, RowEventGate.DATA_ROW_CHANGED);
    outbox.setString(3, source);
    outbox.setString(4, fqn);
    outbox.setString(5, hash);
    // Omit run_id when absent rather than emitting the string "null" — an ambiguous value to consumers.
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("entity", fqn);
    payload.put("entity_key", hash);
    payload.put("op", "insert");
    if (runId != null) payload.put("run_id", runId.toString());
    outbox.setString(6, mapper.writeValueAsString(payload));
    outbox.addBatch();
  }

  // ── SQL / binding shared by both paths ───────────────────────────────────────

  private String conflictClause(MappingTarget.Table spec) {
    return switch (spec.onConflict()) {
      case SKIP   -> " ON CONFLICT (_row_hash) DO NOTHING";
      case UPDATE -> " ON CONFLICT (_row_hash) DO UPDATE SET _ingested_at = now()";
      case ERROR  -> "";
    };
  }

  /** Builds INSERT with {@code rowCount} value tuples; appends RETURNING on the emitting path. */
  private String buildInsertSql(MappingTarget.Table spec, List<String> schemaCols,
                                String conflictClause, boolean returning, int rowCount) {
    StringBuilder sql = new StringBuilder("INSERT INTO ").append(fqn(spec)).append(" (");
    boolean first = true;
    for (String col : FIXED_COLS) {
      if (!first) sql.append(',');
      sql.append(quoteIdent(col));
      first = false;
    }
    for (String col : schemaCols) {
      sql.append(',').append(quoteIdent(col));
    }
    sql.append(") VALUES ");
    int totalCols = FIXED_COLS.size() + schemaCols.size();
    for (int row = 0; row < rowCount; row++) {
      if (row > 0) sql.append(',');
      sql.append('(');
      for (int i = 0; i < totalCols; i++) {
        if (i > 0) sql.append(',');
        sql.append('?');
      }
      sql.append(')');
    }
    sql.append(conflictClause);
    if (returning) sql.append(" RETURNING _row_hash, (xmax = 0) AS inserted");
    return sql.toString();
  }

  /** Binds one row's columns into ps starting at {@code base} (0-based offset; +1 for JDBC index). */
  private void bindRow(PreparedStatement ps, int base, Map<String, Object> row,
                       Map<String, Object> s, Long tenantId, List<String> schemaCols) throws SQLException {
    ps.setString(base + 1, (String) s.get(ROW_HASH));
    ps.setString(base + 2, (String) s.getOrDefault(SOURCE, "unknown"));
    ps.setObject(base + 3, s.get(RUN_ID));
    ps.setInt(base + 4, (int) s.getOrDefault(MAPPING_VERSION, 1));
    ps.setLong(base + 5, tenantId);
    for (int i = 0; i < schemaCols.size(); i++) {
      ps.setObject(base + 6 + i, toJdbc(row.get(schemaCols.get(i))));
    }
  }

  private UUID runIdOf(List<Map<String, Object>> sys) {
    // defensive: write() returns early on empty rows, so sys is normally non-empty here.
    return sys.isEmpty() ? null : (UUID) sys.get(0).get(RUN_ID);
  }

  private String fqnUnquoted(MappingTarget.Table spec) {
    return spec.schema() + "." + spec.name();
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  /** Serializes List values to JSON strings so Postgres TEXT/JSONB columns accept them. */
  private Object toJdbc(Object v) {
    if (v == null) return null;
    if (v instanceof List<?>) {
      try {
        return mapper.writeValueAsString(v);
      } catch (Exception e) {
        return v.toString();
      }
    }
    return v;
  }

  private void exec(String sql) {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute(sql);
    } catch (Exception e) {
      throw new ConnectorException("DDL failed: " + sql + " — " + e.getMessage(), e);
    }
  }

  private String fqn(MappingTarget.Table spec) {
    return quoteIdent(spec.schema()) + '.' + quoteIdent(spec.name());
  }
}
