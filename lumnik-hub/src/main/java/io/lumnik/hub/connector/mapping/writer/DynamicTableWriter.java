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
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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

  private static final Logger LOG = Logger.getLogger(DynamicTableWriter.class);

  @Inject SchemaInferrer inferrer;
  @Inject DynamicTableRegistry registry;
  @Inject RunTableStats stats;
  @Inject RowEventGate gate;
  @Inject ObjectMapper mapper;
  @Inject DriftDetector driftDetector;
  @Inject SourceKeyIndex keyIndex;

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
    if (spec.marksSeen()) refuseSeenRunCollision(rows);
    if (spec.key() != null) SourceKeyIndex.refuseRepeatedKeyInChunk(spec, rows, systemContexts);

    String mappingId = (String) systemContexts.get(0).get("_mapping");

    Map<String, String> registered = registry.findSchema(tenantId, mappingId, connectorId);
    registered = registered == null
        ? createSchema(spec, rows, sourceTypeHints, tenantId, mappingId, connectorId)
        : evolveSchema(spec, rows, registered, sourceTypeHints, tenantId, mappingId, connectorId);
    if (spec.key() != null) {
      stats.recordRemoved(runIdOf(systemContexts), keyIndex.ensure(spec, sourceOf(systemContexts), tenantId));
      keyIndex.refuseRepeatedKeyInRun(spec, rows, systemContexts, registered, tenantId, sourceOf(systemContexts),
          runIdOf(systemContexts));
    }

    Written w = insertRows(spec, rows, systemContexts, tenantId, registered, connectorId);
    if (spec.marksSeen()) markSeen(spec, systemContexts, tenantId);
    // Recorded here, once, whatever insert path ran: the incremental resolution pass reads only the
    // tables a run recorded (#904), so a path stamping _run_id unrecorded would go unresolved.
    stats.recordCounts(runIdOf(systemContexts), fqnUnquoted(spec), w.written(), w.skipped());
  }

  /**
   * First write for THIS mapping: {@code registry.findSchema} came back null, but the physical
   * table may not be new — a sibling mapping can already have created {@code spec}'s table, an
   * earlier registry row for this same mapping can have been deleted with the table left
   * standing, or a CONCURRENT first write from another mapping onto the same new table can be
   * racing this one right now (connector runs fan out onto virtual threads). {@code CREATE TABLE
   * IF NOT EXISTS} is then a silent no-op for whichever call loses that race, so registering
   * whatever THIS chunk infers would let the registry disagree with the table it is supposed to
   * describe from the very first write — the gap #525 named.
   *
   * <p>A {@code pg_advisory_xact_lock} keyed by {@code schema.table} removes the concurrent case
   * outright: only one writer at a time runs this body for a given physical table, so
   * {@code CREATE TABLE IF NOT EXISTS} is a genuine, uncontended existence check exactly like the
   * sequential case, and {@link DriftDetector#actualSchema} — read only AFTER creation, never
   * before — sees the table whichever writer created it. Two earlier review rounds tried to reach
   * this by catching the specific SQLSTATEs a lost "IF NOT EXISTS" race can surface as; that could
   * not stay correct, because {@code createIndexes()} can raise that exact same SQLSTATE for a
   * genuine unique constraint violated by real duplicate data, which must still fail the write.
   *
   * <p>Everything the locked body touches — the create DDL, the physical-schema read, and the
   * registry write — runs on this ONE connection {@code c}, never a second one checked out from
   * the same pool while this one sits idle: round 3 review found that shape starves the admin
   * pool once enough concurrent first-writes to DISTINCT tables are each holding a lock connection
   * and blocking on a second. {@code pg_advisory_xact_lock} then releases automatically on commit
   * or rollback, including the exceptional path.
   */
  private Map<String, String> createSchema(MappingTarget.Table spec, List<Map<String, Object>> rows,
                                           Map<String, String> sourceTypeHints, Long tenantId,
                                           String mappingId, UUID connectorId) {
    try (Connection c = adminDs.getConnection()) {
      c.setAutoCommit(false);
      try {
        firstWriteLock(c, spec.schema(), spec.name());
        refuseSecondWriter(c, spec, tenantId, connectorId);

        Map<String, String> inferred = inferrer.infer(rows, sourceTypeHints);
        createTable(c, spec, inferred);
        createIndexes(c, spec);
        enableRLS(c, spec);

        Map<String, String> actual = driftDetector.actualSchema(c, spec.schema(), spec.name());
        actual.keySet().removeAll(SYSTEM_COLUMNS);
        if (spec.marksSeen()) actual.keySet().remove(SEEN_RUN);   // elsewhere a column of that name is the source's
        Map<String, String> reconciled = applyDrift(c, spec, rows, actual, sourceTypeHints);

        registry.register(c, tenantId, mappingId, spec.schema(), spec.name(), reconciled, connectorId);
        c.commit();
        return reconciled;
      } catch (ConfigValidationException e) {
        rollbackQuietly(c);
        throw e;
      } catch (Exception e) {
        rollbackQuietly(c);
        throw new ConnectorException("DynamicTableWriter.createSchema: " + e.getMessage(), e);
      }
    } catch (SQLException e) {
      throw new ConnectorException("DynamicTableWriter.createSchema: " + e.getMessage(), e);
    }
  }

  /**
   * A hub table is named after its source, so a second connector of the tenant can reach a table
   * another one writes without anyone deciding it; their rows would merge and their scopes with
   * them (#1199). Refused at this connector's first write, naming the holder. A connector already
   * registered for the table never gets here: a sharing that predates the refusal keeps working.
   * Per connector type, the gesture that names another hub table, as in
   * {@link #refuseSystemColumnCollisions}.
   */
  private void refuseSecondWriter(Connection c, MappingTarget.Table spec, Long tenantId, UUID connectorId)
      throws SQLException {
    if (connectorId == null) return;
    String holder = registry.otherWriter(c, tenantId, spec.schema(), spec.name(), connectorId);
    if (holder == null) return;
    throw new ConfigValidationException(
        fqnUnquoted(spec) + " is already written by connector '" + holder + "'; a second connector "
        + "cannot write it — give this one its own hub table: "
        + "jdbc: source.tables[].target; "
        + "csv: the table is named after the connector, rename it; "
        + "kafka: the endpoint's name");
  }

  /** Best-effort: the original failure above already carries the real cause. */
  private void rollbackQuietly(Connection c) {
    try {
      c.rollback();
    } catch (SQLException ignore) {
      // best-effort
    }
  }

  /** The lock a table's first write holds; a connector delete takes it too before dropping the table. */
  static void firstWriteLock(Connection c, String schema, String table) throws SQLException {
    advisory(c, "pg_advisory_xact_lock", schema, table);
  }

  /**
   * The same lock, shared and held by the session: a write that registers outside
   * {@link #firstWriteLock} (the hybrid writer) holds it for the whole write, so a connector delete
   * deciding whether to drop the table waits for it, and writers never wait on each other (#1186).
   * Pair it with {@link #releaseWriteLock} in a {@code finally}: a pooled connection keeps it otherwise.
   */
  static void writeLock(Connection c, String schema, String table) throws SQLException {
    advisory(c, "pg_advisory_lock_shared", schema, table);
  }

  static void releaseWriteLock(Connection c, String schema, String table) throws SQLException {
    advisory(c, "pg_advisory_unlock_shared", schema, table);
  }

  private static void advisory(Connection c, String function, String schema, String table) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT " + function + "(hashtextextended(?, 0))")) {
      ps.setString(1, schema + "." + table);
      ps.execute();
    }
  }

  /**
   * Subsequent writes: ALTER TABLE for any columns not seen before, then re-register the schema.
   *
   * <p>The new column is typed from the chunk that introduces it, by the same inferrer and the
   * same source hints the first chunk used. It used to be added as TEXT, always: type inference
   * ran only in {@link #createSchema}, so a number or a boolean a producer added on day two froze
   * to its string form — {@code "42"}, {@code "true"} — and a nested object to JSON text rather
   * than jsonb, silently, with no warning and no report. Schema drift is a kafka topic's daily
   * bread and a jdbc source gains columns the same way, so "later" is not a rare path (#485).
   *
   * <p>The bound is the chunk: inference reads only the rows in hand, exactly as creation does,
   * and a column all-NULL across them falls back to its source hint and then to TEXT. The line is
   * logged because a schema decision taken from a partial sample is one an operator may want to
   * revisit — that gesture is `lm endpoint reset-cursor` once the producer has settled.
   */
  private Map<String, String> evolveSchema(MappingTarget.Table spec, List<Map<String, Object>> rows,
                                           Map<String, String> registered,
                                           Map<String, String> sourceTypeHints, Long tenantId,
                                           String mappingId, UUID connectorId) {
    Map<String, String> updated = applyDrift(spec, rows, registered, sourceTypeHints);
    if (updated.equals(registered)) return registered;

    // Run all DDL BEFORE advancing the registry — mirrors the creation branch, where register()
    // is the last step (the "commit" of successful DDL). If createIndexes throws, the registry
    // stays in its pre-evolution state so the next run re-detects newCols and retries; ALTER and
    // index DDL are both IF NOT EXISTS, so the retry is safe.
    registry.register(tenantId, mappingId, spec.schema(), spec.name(), updated, connectorId);
    return updated;
  }

  /** {@link #applyDrift(Connection, MappingTarget.Table, List, Map, Map)} on its own connection —
   *  {@link #evolveSchema}'s unlocked call, where no wider transaction is already in hand. */
  private Map<String, String> applyDrift(MappingTarget.Table spec, List<Map<String, Object>> rows,
                                         Map<String, String> base, Map<String, String> sourceTypeHints) {
    try (Connection c = adminDs.getConnection()) {
      return applyDrift(c, spec, rows, base, sourceTypeHints);
    } catch (SQLException e) {
      throw new ConnectorException("DynamicTableWriter.applyDrift: " + e.getMessage(), e);
    }
  }

  /**
   * Widens too-narrow columns then adds new ones against {@code base}, issuing whatever ALTER DDL
   * each requires, and returns the resulting schema — equal to {@code base} (by content) when
   * this chunk drove no change. {@code base} is registry truth for {@link #evolveSchema}'s own
   * call and physical truth (via {@link DriftDetector}) for {@link #createSchema}'s — the one
   * place #525 found the two disagreeing.
   */
  private Map<String, String> applyDrift(Connection c, MappingTarget.Table spec, List<Map<String, Object>> rows,
                                         Map<String, String> base, Map<String, String> sourceTypeHints)
                                         throws SQLException {
    Set<String> newCols = detectNewColumns(rows, base);
    Set<String> tooNarrow = widenings(rows, base);
    if (newCols.isEmpty() && tooNarrow.isEmpty()) return base;

    Map<String, String> updated = new LinkedHashMap<>(base);

    if (!tooNarrow.isEmpty()) {
      widenColumns(c, spec, tooNarrow);
      for (String col : tooNarrow) updated.put(col, NUMERIC);
      LOG.infof("%s.%s: %d column(s) widened to %s by this chunk's first fractional value: %s",
          spec.schema(), spec.name(), tooNarrow.size(), NUMERIC, String.join(", ", tooNarrow));
    }

    if (!newCols.isEmpty()) {
      Map<String, String> inferred = inferrer.infer(rows, sourceTypeHints);
      for (String col : newCols) {
        String pgType = inferred.getOrDefault(col, "TEXT");
        alterAddColumn(c, spec, col, pgType);
        updated.put(col, pgType);
      }
      // In the order they were ADDED, which is now the chunk's own order and therefore the
      // table's — the sort this line used to carry existed only to make an unordered set
      // deterministic, and it read as a different order from the one `\d` shows.
      LOG.infof("%s.%s: %d column(s) added mid-run, typed from this chunk: %s",
          spec.schema(), spec.name(), newCols.size(),
          newCols.stream().map(col -> col + " " + updated.get(col)).collect(Collectors.joining(", ")));
      // Index DDL only has something to do when a column appeared; widening one changes no index.
      createIndexes(c, spec);
    }

    return updated;
  }

  /**
   * Columns this chunk carries that the registry has never seen, in the order the chunk presents
   * them.
   *
   * <p>ORDERED, and that is load-bearing: {@code evolveSchema} adds them by iterating this set,
   * and Postgres's physical column order follows the order columns are ADDED. A {@code HashSet}
   * here meant a producer adding three fields on day two got them in hash order — the exact
   * scramble #496 removed from the creation path, left standing on the path schema drift actually
   * takes (caught in review).
   */
  private Set<String> detectNewColumns(List<Map<String, Object>> rows, Map<String, String> registered) {
    Set<String> newCols = new LinkedHashSet<>();
    for (var r : rows) {
      for (String k : r.keySet()) {
        if (!registered.containsKey(k)) newCols.add(k);
      }
    }
    return newCols;
  }

  private void alterAddColumn(Connection c, MappingTarget.Table spec, String column, String pgType)
      throws SQLException {
    exec(c, "ALTER TABLE " + fqn(spec) + " ADD COLUMN IF NOT EXISTS " + quoteIdent(column) + " " + pgType);
  }

  /** The widening pair, read from the inferrer that emits them: {@code bigint -> numeric(18,4)}. */
  private static final String BIGINT = SchemaInferrer.BIGINT;
  private static final String NUMERIC = SchemaInferrer.NUMERIC;

  /**
   * The registered {@code BIGINT} columns this chunk feeds a number that is not whole.
   *
   * <p>{@link SchemaInferrer} types a column of whole numbers {@code BIGINT} so a whole number
   * reads as one (#497) — but it only ever sees the chunk in hand, and a {@code credit_limit} of
   * {@code 1000, 2000} in the first chunk can be {@code 748.5} in the fifth. Without this, that
   * fifth chunk's INSERT would die ("column is of type bigint but expression is of type double
   * precision") and take the run with it, for what is a cosmetic gain.
   *
   * <p>ONE direction and ONE pair. {@code bigint → numeric(18,4)} loses nothing, so it needs no
   * arbitration; every other drift stays what it was — the first chunk types the column, and a
   * value that cannot be bound to it is the source contradicting itself. Pure and
   * package-visible so the detection can be pinned without a datasource.
   */
  static Set<String> widenings(List<Map<String, Object>> rows, Map<String, String> registered) {
    Set<String> out = new LinkedHashSet<>();
    for (Map<String, Object> row : rows) {
      for (Map.Entry<String, Object> e : row.entrySet()) {
        if (BIGINT.equals(registered.get(e.getKey()))
            && e.getValue() instanceof Number && !SchemaInferrer.isIntegral(e.getValue())) {
          out.add(e.getKey());
        }
      }
    }
    return out;
  }

  /**
   * Widens every too-narrow column of this chunk in ONE statement.
   * {@code ALTER COLUMN TYPE} rewrites the whole table under an ACCESS EXCLUSIVE lock, so a
   * statement per column would rewrite it once per column and take the lock once per column —
   * three narrowed columns discovered together, three full rewrites. Postgres accepts the
   * comma-separated form and pays for one. (Its {@code ADD COLUMN} neighbour above stays a
   * statement per column on purpose: adding a column without a default is metadata-only.)
   */
  private void widenColumns(Connection c, MappingTarget.Table spec, Set<String> columns) throws SQLException {
    String alters = columns.stream()
        .map(col -> "ALTER COLUMN " + quoteIdent(col) + " TYPE " + NUMERIC)
        .collect(Collectors.joining(", "));
    exec(c, "ALTER TABLE " + fqn(spec) + " " + alters);
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
  /**
   * The last run that wrote or met the row again (#1195) — only on a table whose writer
   * {@code marksSeen}, so it is not one of {@link #SYSTEM_COLUMNS}: every other table, and every
   * page that shows one, keeps the seven.
   */
  static final String SEEN_RUN = "_seen_run";
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
   * The system columns as a message renders them: comma-separated, alphabetical. The TreeSet is
   * load-bearing — {@code SYSTEM_COLUMNS} is a {@code Set.of()} whose iteration order is
   * unspecified, so rendering it directly makes any test asserting the message flaky. Written
   * once here, where the set lives, because three call sites had copied the same subtlety.
   */
  public static String systemColumnList() {
    return String.join(", ", new TreeSet<>(SYSTEM_COLUMNS));
  }

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
              + "(" + systemColumnList() + "); "
              + "it must reach the hub under another name — "
              + "jdbc: source.tables[].rename; "
              + "csv: a map-columns row hook under spec.mapping; "
              + "kafka: no rename exists, the field must be renamed in the topic payload");
        }
      }
    }
  }

  /** The one column a full-refresh table adds to the seven: a source column of that name cannot be written either. */
  static void refuseSeenRunCollision(List<Map<String, Object>> rows) {
    for (Map<String, Object> row : rows) {
      if (row.containsKey(SEEN_RUN)) {
        throw new ConfigValidationException("source column '" + SEEN_RUN + "' collides with the column a "
            + "full refresh keeps on this table; it must reach the hub under another name — "
            + "csv: a map-columns row hook under spec.mapping");
      }
    }
  }

  private void createTable(Connection c, MappingTarget.Table spec, Map<String, String> schema)
      throws SQLException {
    exec(c, "CREATE SCHEMA IF NOT EXISTS " + quoteIdent(spec.schema()));
    exec(c, createTableDdl(spec, schema));
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
     .append("_row_hash TEXT NOT NULL, ")
     .append("_ingested_at TIMESTAMPTZ NOT NULL DEFAULT now(), ")
     .append("_source TEXT, ")
     .append("_run_id UUID, ")
     .append("_mapping_version INT, ")
     .append(spec.marksSeen() ? SEEN_RUN + " UUID, " : "")
     .append("tenant_id BIGINT NOT NULL");
    for (var e : schema.entrySet()) {
      t.append(", ").append(quoteIdent(e.getKey())).append(' ').append(e.getValue());
    }
    // Per tenant: the table holds every tenant's rows, and the hash covers the row's content only (#1198).
    t.append(", UNIQUE (tenant_id, _row_hash))");
    return t.toString();
  }

  private void createIndexes(Connection c, MappingTarget.Table spec) throws SQLException {
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
      exec(c, s.toString());
    }
  }

  /**
   * Stamps the shared tenant-isolation DDL ({@link RlsDdl}) on a freshly created table.
   * Package-visible so the emitted DDL can be pinned without a live datasource ({@code
   * RlsDdlCharacterizationTest} calls this exact connectionless overload against a mocked
   * {@code adminDs}).
   */
  void enableRLS(MappingTarget.Table spec) {
    try (Connection c = adminDs.getConnection()) {
      enableRLS(c, spec);
    } catch (SQLException e) {
      throw new ConnectorException("DDL failed: " + e.getMessage(), e);
    }
  }

  private void enableRLS(Connection c, MappingTarget.Table spec) throws SQLException {
    String schema = spec.schema();
    String table = spec.name();
    exec(c, RlsDdl.enableRowLevelSecurity(schema, table));
    exec(c, RlsDdl.tenantIsolationPolicy(schema, table));
    exec(c, RlsDdl.tenantIdIndex(schema, table));
    // One guarded block per grant: each stands or falls on its own, so a missing lumnik_app never
    // costs the table its lumnik_readonly grant.
    exec(c, RlsDdl.guarded(RlsDdl.grantAppDml(schema, table)));
    exec(c, RlsDdl.guarded(RlsDdl.grantReadonlySelect(schema, table)));
    exec(c, RlsDdl.guarded(RlsDdl.grantSequenceUsage(schema, table + "_id_seq")));
  }

  // ── DML ────────────────────────────────────────────────────────────────────

  /** Tables known to carry {@link #SEEN_RUN}: checked once per table per JVM, not per chunk. */
  private final Set<String> seenColumnReady = ConcurrentHashMap.newKeySet();

  /**
   * Stamps this run on every row of the chunk it wrote or met again (#1195, see {@code CsvFullRefresh}).
   * A separate UPDATE after the insert, not a {@code DO UPDATE} touch: the insert, its counts and its
   * events stay what they were; {@code _run_id} keeps naming the run that LANDED the row (the #1185
   * entity view and the incremental resolution read it); and {@code _ingested_at} (Search marks,
   * freshness) does not move for a row that did not change. Only this source's rows are stamped.
   */
  private void markSeen(MappingTarget.Table spec, List<Map<String, Object>> sys, Long tenantId) {
    UUID runId = runIdOf(sys);
    if (runId == null) return;
    String[] hashes = sys.stream().map(m -> (String) m.get(ROW_HASH)).distinct().toArray(String[]::new);
    try (Connection c = adminDs.getConnection()) {
      if (!seenColumnReady.contains(fqnUnquoted(spec))) {
        // A table created before #1195. Asked first: ADD COLUMN takes an exclusive lock even when
        // the column is there, and would queue behind every reader of the table after a restart.
        if (!driftDetector.actualSchema(c, spec.schema(), spec.name()).containsKey(SEEN_RUN)) {
          exec(c, "ALTER TABLE " + fqn(spec) + " ADD COLUMN IF NOT EXISTS " + SEEN_RUN + " UUID");
        }
        seenColumnReady.add(fqnUnquoted(spec));
      }
      try (PreparedStatement ps = c.prepareStatement("UPDATE " + fqn(spec) + " SET " + SEEN_RUN + " = ? "
          + "WHERE tenant_id = ? AND _source IS NOT DISTINCT FROM ? AND _row_hash = ANY (?)")) {
        ps.setObject(1, runId);
        ps.setLong(2, tenantId);
        ps.setString(3, (String) sys.get(0).get(SOURCE));
        ps.setArray(4, c.createArrayOf("text", hashes));
        ps.executeUpdate();
      }
    } catch (SQLException e) {
      throw new ConnectorException("DynamicTableWriter.markSeen failed: " + e.getMessage(), e);
    }
  }

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

  /** What one insert path wrote: rows inserted (or touched by DO UPDATE) and rows it skipped. */
  private record Written(int written, int skipped) {}

  /** Dispatcher: gated path emits per-new-row events in the data tx; un-gated path is the plain batch. */
  private Written insertRows(MappingTarget.Table spec, List<Map<String, Object>> rows,
                             List<Map<String, Object>> sys, Long tenantId,
                             Map<String, String> schema, UUID connectorId) {
    return gate.enabled(tenantId, fqnUnquoted(spec))
        ? insertRowsEmitting(spec, rows, sys, tenantId, schema, connectorId)
        : insertRowsBatched(spec, rows, sys, tenantId, schema);
  }

  /** Un-gated path (default): pgjdbc batch + Task-1 written/skipped counts. Unchanged behavior. */
  private Written insertRowsBatched(MappingTarget.Table spec, List<Map<String, Object>> rows,
                                    List<Map<String, Object>> sys, Long tenantId,
                                    Map<String, String> schema) {
    List<String> schemaCols = new ArrayList<>(schema.keySet());
    String sql = buildInsertSql(spec, schemaCols, conflictClause(spec, schemaCols, sys, tenantId), false, 1);

    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(sql)) {
      for (int r = 0; r < rows.size(); r++) {
        bindRow(ps, 0, rows.get(r), sys.get(r), tenantId, schemaCols, schema);
        ps.addBatch();
      }
      int[] counts = ps.executeBatch();
      int written = 0;
      int skipped = 0;
      for (int n : counts) {
        // pgjdbc returns 1 for a real insert, 0 for an ON CONFLICT DO NOTHING no-op, and -2 (SUCCESS_NO_INFO) when it can't report a per-row count; treat anything != 0 as written, 0 as skipped.
        if (n != 0) written++; else skipped++;
      }
      return new Written(written, skipped);
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
  private Written insertRowsEmitting(MappingTarget.Table spec, List<Map<String, Object>> rows,
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
          SubBatchResult sb = writeSubBatch(c, outbox, spec, rows, sys, tenantId, schemaCols, schema,
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
    return new Written(written, skipped);
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
                            Long tenantId, List<String> schemaCols, Map<String, String> schema,
                            List<Integer> idx,
                            int start, int end, String fqn, String source, UUID runId)
                            throws SQLException, JsonProcessingException {
    int n = end - start;
    String sql = buildInsertSql(spec, schemaCols, conflictClause(spec, schemaCols, sys, tenantId), true, n);
    int written = 0;
    int returned = 0;
    int colsPerRow = FIXED_COLS.size() + schemaCols.size();
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      for (int k = start; k < end; k++) {
        int r = idx.get(k);
        bindRow(ps, (k - start) * colsPerRow, rows.get(r), sys.get(r), tenantId, schemaCols, schema);
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          returned++;
          String hash = rs.getString(1);
          boolean inserted = rs.getBoolean(2);
          // UPDATE touches (inserted=false) count as written too, to match the un-gated path.
          written++;
          // A keyed table returns a row only when it changed (#1196): its new version is an event too.
          if (inserted || spec.key() != null) {
            stageEvent(outbox, tenantId, source, fqn, hash, runId, inserted ? "insert" : "update");
          }
        }
      }
    }
    return new SubBatchResult(written, returned);
  }

  private void stageEvent(PreparedStatement outbox, Long tenantId, String source, String fqn, String hash,
                          UUID runId, String op) throws SQLException, JsonProcessingException {
    outbox.setLong(1, tenantId);
    outbox.setString(2, RowEventGate.DATA_ROW_CHANGED);
    outbox.setString(3, source);
    outbox.setString(4, fqn);
    outbox.setString(5, hash);
    // Omit run_id when absent rather than emitting the string "null" — an ambiguous value to consumers.
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("entity", fqn);
    payload.put("entity_key", hash);
    payload.put("op", op);
    if (runId != null) payload.put("run_id", runId.toString());
    outbox.setString(6, mapper.writeValueAsString(payload));
    outbox.addBatch();
  }

  // ── SQL / binding shared by both paths ───────────────────────────────────────

  private String conflictClause(MappingTarget.Table spec, List<String> schemaCols, List<Map<String, Object>> sys,
                                long tenantId) {
    if (spec.key() != null) return upsertClause(spec, schemaCols, tenantId, sourceOf(sys));
    return switch (spec.onConflict()) {
      case SKIP   -> " ON CONFLICT (tenant_id, _row_hash) DO NOTHING";
      case UPDATE -> " ON CONFLICT (tenant_id, _row_hash) DO UPDATE SET _ingested_at = now()";
      case ERROR  -> "";
    };
  }

  /**
   * A keyed table (#1196): a row whose key is there replaces it when its content differs, and is skipped
   * when it does not — so an unchanged row keeps its run, its ingestion time and its id. The key index is
   * partial, over this tenant's rows of this source, and is named as the arbiter by repeating its predicate.
   */
  static String upsertClause(MappingTarget.Table spec, List<String> schemaCols, long tenantId, String source) {
    StringBuilder set = new StringBuilder("_row_hash = EXCLUDED._row_hash, _run_id = EXCLUDED._run_id, "
        + "_mapping_version = EXCLUDED._mapping_version, _ingested_at = now()");
    for (String col : schemaCols) {
      if (!spec.key().contains(col)) set.append(", ").append(quoteIdent(col)).append(" = EXCLUDED.").append(quoteIdent(col));
    }
    return " ON CONFLICT (" + SourceKeyIndex.columns(spec) + ") WHERE " + SourceKeyIndex.predicate(tenantId, source)
        + " DO UPDATE SET " + set + " WHERE " + quoteIdent(spec.name()) + "._row_hash IS DISTINCT FROM EXCLUDED._row_hash";
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
                       Map<String, Object> s, Long tenantId, List<String> schemaCols,
                       Map<String, String> schema) throws SQLException {
    ps.setString(base + 1, (String) s.get(ROW_HASH));
    ps.setString(base + 2, (String) s.getOrDefault(SOURCE, "unknown"));
    ps.setObject(base + 3, s.get(RUN_ID));
    ps.setInt(base + 4, (int) s.getOrDefault(MAPPING_VERSION, 1));
    ps.setLong(base + 5, tenantId);
    for (int i = 0; i < schemaCols.size(); i++) {
      String col = schemaCols.get(i);
      bindValue(ps, base + 6 + i, row.get(col), schema.get(col));
    }
  }

  /** The source every row of a chunk carries, as {@link #bindRow} writes it. */
  private static String sourceOf(List<Map<String, Object>> sys) {
    return (String) sys.get(0).getOrDefault(SOURCE, "unknown");
  }

  private UUID runIdOf(List<Map<String, Object>> sys) {
    // defensive: write() returns early on empty rows, so sys is normally non-empty here.
    return sys.isEmpty() ? null : (UUID) sys.get(0).get(RUN_ID);
  }

  private String fqnUnquoted(MappingTarget.Table spec) {
    return spec.schema() + "." + spec.name();
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  /**
   * Binds one cell. A nested value goes as its JSON text under {@code Types.OTHER}, which makes
   * pgJDBC send it untyped so Postgres reads it as the column's own type — the idiom
   * {@link HybridJsonbWriter} already uses, and the reason the INSERT builder can stay type-blind
   * and positional.
   *
   * <p>Sending it any other way does not work, measured both ways: a plain {@code setObject} of the
   * JSON String reaches a jsonb column as {@code character varying} and Postgres refuses it, and a
   * raw {@code Map} hits pgJDBC's hstore mapping and dies before Postgres sees anything. Both threw,
   * and a throw here becomes a {@code ChunkResult.Failure} — cursor unmoved, nothing dead-lettered,
   * the retry replaying the same failure.
   */
  private void bindValue(PreparedStatement ps, int idx, Object v, String columnType) throws SQLException {
    if (v instanceof List<?> || v instanceof Map<?, ?>) {
      ps.setObject(idx, toJson(v), Types.OTHER);
      return;
    }
    ps.setObject(idx, bindable(v, columnType));
  }

  /**
   * pgJDBC infers a SQL type from the Java type it is handed, and its {@code java.time} support
   * does not cover every temporal {@link SchemaInferrer#isTimestamp} accepts: an {@code Instant}
   * or {@code ZonedDateTime} reaches a bare {@code setObject} and pgJDBC refuses it ("Impossible
   * de déduire le type SQL…") — on the FIRST chunk, since the column is already TIMESTAMPTZ before
   * any row is bound. Both convert losslessly to {@code OffsetDateTime}, which pgJDBC does bind to
   * TIMESTAMPTZ, so the inferrer's promise and the binder's capability stay pinned together
   * (#506, {@code SchemaTypingInventoryTest}).
   *
   * <p>A {@code LocalDateTime} (parse-date without a {@code zone}) going into a TIMESTAMPTZ column
   * is stored as UTC: pgJDBC sends it as a {@code timestamp}, which PostgreSQL places in the session
   * zone — the hub JVM's — so the same 23:30 landed 23:30Z on a UTC hub and 21:30Z on a Paris one
   * (#1092). Into any other column (a TEXT column whose first chunk mixed dates and times) it stays
   * as it is: there an offset would be rendered in the session zone, "2026-08-03 01:30:00+02".
   * A {@link WallClockTimestamp} — a jdbc source time with no zone (#1093) — is the same case, read
   * in the zone its connection declares (#1328).
   */
  static Object bindable(Object v, String columnType) {
    boolean timestamptz = "TIMESTAMPTZ".equals(columnType);
    return switch (v) {
      case Instant instant -> instant.atOffset(ZoneOffset.UTC);
      case LocalDateTime local when timestamptz -> local.atOffset(ZoneOffset.UTC);
      case WallClockTimestamp wallClock when timestamptz -> wallClock.instant();
      case ZonedDateTime zdt -> zdt.toOffsetDateTime();
      case null, default -> v;
    };
  }

  /**
   * The value as JSON text, or a failure that says so.
   *
   * <p>It used to fall back to {@code v.toString()}, which for a nested value is not JSON —
   * {@code [java.lang.Object@1a2b]}, {@code {k=v}} — and reached the jsonb column as
   * "invalid input syntax for type json": the same poison pill this class now avoids, wearing a
   * cause that points at Postgres instead of at the value. {@code HybridJsonbWriter} throws here
   * and so does this.
   */
  private String toJson(Object v) throws SQLException {
    try {
      return mapper.writeValueAsString(v);
    } catch (Exception e) {
      throw new SQLException("DynamicTableWriter: JSON serialization failed", e);
    }
  }

  static void exec(Connection c, String sql) throws SQLException {
    try (Statement st = c.createStatement()) {
      st.execute(sql);
    }
  }

  static String fqn(MappingTarget.Table spec) {
    return quoteIdent(spec.schema()) + '.' + quoteIdent(spec.name());
  }
}
