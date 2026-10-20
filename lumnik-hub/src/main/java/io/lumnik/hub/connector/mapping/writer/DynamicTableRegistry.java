package io.lumnik.hub.connector.mapping.writer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.extraction.spi.ExtractionSink;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jboss.logging.Logger;

import static io.lumnik.hub.sql.SqlIdentifiers.quoteIdent;

/**
 * Stores per-tenant per-mapping schema metadata for dynamic tables.
 * Uses the admin datasource (BYPASSRLS) for cross-tenant operations.
 */
@ApplicationScoped
public class DynamicTableRegistry {

  private static final ObjectMapper M = new ObjectMapper();
  private static final Logger LOG = Logger.getLogger(DynamicTableRegistry.class);

  /** Hub dynamic tables are created by every built-in connector type as
   * {@code connector.t_<name>} (see CsvFile/Jdbc/KafkaConnectorType). purge only
   * drops tables matching this convention, so a corrupted/foreign registry row can
   * never make it drop a core/identity/platform table (blast-radius bound). */
  static final String DYNAMIC_SCHEMA = "connector";
  static final String DYNAMIC_PREFIX = "t_";
  /** The hybrid writer's schema — entirely hub-owned (created only by HybridJsonbWriter),
   *  so every registered ext.* row is droppable with its connector (C4/P18 PR1). */
  static final String HYBRID_SCHEMA = "ext";
  /** {@code mapping_id} marker {@link HybridJsonbWriter} registers its targets under — the one
   *  field that tells the two row writers apart in this table (the schema does not: a hybrid spec
   *  with no schema registers under {@code public}, and a mapping may target any schema). */
  static final String HYBRID_MAPPING_PREFIX = "hybrid:";

  static boolean isHubDynamicTable(String schema, String name) {
    if (name == null) return false;
    return (DYNAMIC_SCHEMA.equals(schema) && name.startsWith(DYNAMIC_PREFIX))
        || HYBRID_SCHEMA.equals(schema);
  }

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  @Inject Instance<ExtractionSink> sinks;

  @Inject HybridJsonbWriter hybridWriter;

  /**
   * Does a row written into this table emit {@code data.row.changed}? The positive form of the
   * question on purpose: the event is staged by {@link DynamicTableWriter} alone, so the answer is
   * "there is a registry row for it, and it is not one the hybrid writer put there". Everything
   * else — a fused {@code connector.v_} entity view, an {@code ext.*} hybrid target, a table the
   * card grew from some future source — answers false, so drift refuses instead of going silent.
   *
   * <p>That silence is what this exists to end: a subscription declared on a table nothing writes
   * rows into is accepted, listed, and never heard from again.
   *
   * @param fqn schema-qualified, exactly as the writer stamps it on the event's {@code entity}
   */
  public boolean emitsRowEvents(Long tenantId, String fqn) {
    if (tenantId == null || fqn == null) return false;
    int dot = fqn.indexOf('.');
    if (dot <= 0 || dot == fqn.length() - 1) return false;
    String sql = "SELECT 1 FROM connector.dynamic_table "
        + "WHERE tenant_id = ? AND table_schema = ? AND table_name = ? "
        + "AND mapping_id NOT LIKE ? LIMIT 1";
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setLong(1, tenantId);
      ps.setString(2, fqn.substring(0, dot));
      ps.setString(3, fqn.substring(dot + 1));
      ps.setString(4, HYBRID_MAPPING_PREFIX + "%");
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    } catch (Exception e) {
      throw new RuntimeException("DynamicTableRegistry.emitsRowEvents: " + e.getMessage(), e);
    }
  }

  /**
   * The scopes of every connector writing one of the tables {@code connectorId} writes, its own
   * included: the cards a change in those tables' writers changes (#1186). A table holds the rows of
   * each of its writers, and a card shows it only while every one of them carries the card's scope.
   */
  public List<String> scopesOfTablesWrittenBy(long tenantId, UUID connectorId) {
    return Jdbc.query(adminDs, "DynamicTableRegistry.scopesOfTablesWrittenBy",
        "SELECT DISTINCT unnest(c.tags) FROM connector.dynamic_table o "
            + "JOIN connector.connector c ON c.id = o.connector_id AND c.tenant_id = o.tenant_id "
            + "WHERE o.tenant_id = ? AND (o.table_schema, o.table_name) IN "
            + "(SELECT table_schema, table_name FROM connector.dynamic_table WHERE tenant_id = ? AND connector_id = ?)",
        ps -> { ps.setLong(1, tenantId); ps.setLong(2, tenantId); ps.setObject(3, connectorId); },
        rs -> rs.getString(1));
  }

  /**
   * The name of another connector of the tenant already writing {@code schema.table}, or null (#1199).
   * Read on the connection holding {@link DynamicTableWriter#firstWriteLock}, so two first writes
   * cannot both find the table free.
   */
  String otherWriter(Connection c, long tenantId, String schema, String table, UUID connectorId) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT o.name FROM connector.dynamic_table d "
            + "JOIN connector.connector o ON o.id = d.connector_id AND o.tenant_id = d.tenant_id "
            + "WHERE d.tenant_id = ? AND d.table_schema = ? AND d.table_name = ? AND d.connector_id <> ? "
            + "ORDER BY d.created_at LIMIT 1")) {
      ps.setLong(1, tenantId);
      ps.setString(2, schema);
      ps.setString(3, table);
      ps.setObject(4, connectorId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }

  /** Returns the inferred schema {column: pg_type} the connector registered, or null if it has not. */
  public Map<String, String> findSchema(Long tenantId, String mappingId, UUID connectorId) {
    String sql = "SELECT schema_inferred::text FROM connector.dynamic_table " +
                 "WHERE tenant_id = ? AND mapping_id = ? AND connector_id IS NOT DISTINCT FROM ?";
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setLong(1, tenantId);
      ps.setString(2, mappingId);
      ps.setObject(3, connectorId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return M.readValue(rs.getString(1), new TypeReference<Map<String, String>>() {});
        }
      }
    } catch (Exception e) {
      throw new RuntimeException("DynamicTableRegistry.findSchema: " + e.getMessage(), e);
    }
    return null;
  }

  /**
   * The views that block this connector's dynamic-table drops: {@code "schema.table" ->
   * "schema.view"} (both sides schema-qualified), one entry per blocked table (empty when nothing
   * depends on them). Asks Postgres's ACTUAL dependency graph ({@code pg_depend}) over exactly the
   * tables {@code purgeForConnector} will drop (the connector's registry rows) — so the set is
   * precisely the {@code DROP}s Postgres would refuse, no prediction from an in-memory manifest
   * cache that a second hub instance may not have loaded (which would let the anonymous 500 back
   * through). Both plain views ({@code relkind 'v'}) and materialized views ({@code 'm'}) block a
   * {@code DROP TABLE}, so both are caught. Admin datasource (BYPASSRLS); keyed by the globally-
   * unique connector UUID, tenant-safe exactly like purge.
   */
  public Map<String, String> dependentViews(UUID connectorId) {
    Map<String, String> byTable = new LinkedHashMap<>();
    // Canonical pg_depend form: OIDs are unique per catalog, not globally, so pin the catalogs
    // (refclassid = pg_class, classid = pg_rewrite) and take only NORMAL dependencies (deptype 'n',
    // the edge a view's rule holds on the tables it reads).
    String sql =
        "SELECT dt.table_schema, dt.table_name, vns.nspname AS view_schema, v.relname AS view_name "
      + "FROM connector.dynamic_table dt "
      + "JOIN pg_catalog.pg_class tc ON tc.relname = dt.table_name "
      + "JOIN pg_catalog.pg_namespace tns ON tns.oid = tc.relnamespace AND tns.nspname = dt.table_schema "
      + "JOIN pg_catalog.pg_depend d ON d.refobjid = tc.oid "
      + "  AND d.refclassid = 'pg_catalog.pg_class'::regclass "
      + "  AND d.classid = 'pg_catalog.pg_rewrite'::regclass "
      + "  AND d.deptype = 'n' "
      + "JOIN pg_catalog.pg_rewrite rw ON rw.oid = d.objid "
      + "JOIN pg_catalog.pg_class v ON v.oid = rw.ev_class AND v.relkind IN ('v', 'm') AND v.oid <> tc.oid "
      + "JOIN pg_catalog.pg_namespace vns ON vns.oid = v.relnamespace "
      + "WHERE dt.connector_id = ? "
      + "ORDER BY dt.table_schema, dt.table_name, vns.nspname, v.relname";
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setObject(1, connectorId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          byTable.putIfAbsent(rs.getString(1) + "." + rs.getString(2),
              rs.getString(3) + "." + rs.getString(4));
        }
      }
    } catch (Exception e) {
      throw new RuntimeException("DynamicTableRegistry.dependentViews: " + e.getMessage(), e);
    }
    return byTable;
  }

  /** Overload without a connector link (internal/test paths). */
  public void register(Long tenantId, String mappingId, String tableSchema, String tableName,
                       Map<String, String> schema) {
    register(tenantId, mappingId, tableSchema, tableName, schema, null);
  }

  /**
   * Removes what the connector wrote and deletes its registry rows, atomically: each of its
   * dynamic tables is dropped, or — while another writer shares it — only emptied of this
   * connector's rows ({@link #dropIfLastWriter}). Admin datasource (BYPASSRLS); connector_id is a globally-unique UUID
   * so filtering by it alone is tenant-safe.
   *
   * <p>The whole purge runs in ONE transaction (Postgres DDL is transactional): a
   * mid-loop failure rolls back, so we never half-purge (some tables dropped, registry
   * rows left). A single {@code DELETE … RETURNING} both removes the connector's
   * registry rows AND yields the exact set to drop, in one atomic statement — closing
   * the SELECT-then-DELETE race where a row registered concurrently (READ COMMITTED)
   * could be deleted without its table being dropped. DROP quotes the stored identifiers
   * the same way the writer created them (DDL cannot be parameterised) and uses IF EXISTS
   * (idempotent). Only tables matching the hub convention {@code connector.t_*} are
   * dropped — a corrupted/foreign registry row is skipped (logged), bounding blast
   * radius. Any error propagates so the caller can abort the delete rather than half-clean.
   */
  public void purgeForConnector(UUID connectorId) {
    try (Connection c = adminDs.getConnection()) {
      boolean prevAuto = c.getAutoCommit();
      c.setAutoCommit(false);
      try {
        List<String[]> tables = new ArrayList<>();
        try (PreparedStatement del = c.prepareStatement(
            "DELETE FROM connector.dynamic_table WHERE connector_id = ? "
            + "RETURNING table_schema, table_name, tenant_id")) {
          del.setObject(1, connectorId);
          try (ResultSet rs = del.executeQuery()) {
            while (rs.next()) tables.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3)});
          }
        }
        List<String[]> dropped = new ArrayList<>();
        for (String[] t : tables) {
          if (!isHubDynamicTable(t[0], t[1])) {
            LOG.warnf("purgeForConnector(%s): skipping unexpected registry table %s.%s "
                + "(neither %s.%s* nor %s.*); not dropping it", connectorId, t[0], t[1],
                DYNAMIC_SCHEMA, DYNAMIC_PREFIX, HYBRID_SCHEMA);
            continue;
          }
          if (dropIfLastWriter(c, connectorId, t[0], t[1])) dropped.add(t);
          forgetWatermark(c, Long.parseLong(t[2]), t[0], t[1]);
        }
        c.commit();
        // The DROP above ran on the admin datasource with its own commit (JTA enlistment disabled),
        // so it is already durable here: this post-drop cleanup is not part of the caller's
        // transaction and cannot un-drop anything (the delete's atomicity is partial by design).
        //
        // Hybrid tables (ext.*) carry two things that must not outlive the table: HybridJsonbWriter's
        // in-memory schema cache — or a re-ingest under the same name skips CREATE TABLE and fails
        // with "relation … does not exist" — and the one-shot corpus descriptor keyed
        // oneshot:<schema.table> (no connector_id on the sink seam, so no fk cascade reaches it).
        // forget() runs FIRST, before the corpus clear's try/catch, so a corpus-clear failure can
        // never skip the cache invalidation. connector.t_* tables need neither (DynamicTableWriter
        // keeps no in-memory cache), so the HYBRID_SCHEMA filter covers both.
        for (String[] t : dropped) {
          if (!HYBRID_SCHEMA.equals(t[0])) continue;
          hybridWriter.forget(t[0] + "." + t[1]);
          for (ExtractionSink sink : sinks) {
            try {
              sink.clear(Long.parseLong(t[2]), "oneshot:" + t[0] + "." + t[1]);
            } catch (RuntimeException e) {
              LOG.warnf(e, "purgeForConnector(%s): descriptor clear failed for %s.%s",
                  connectorId, t[0], t[1]);
            }
          }
        }
      } catch (Exception e) {
        c.rollback();
        throw e;
      } finally {
        c.setAutoCommit(prevAuto);
      }
    } catch (Exception e) {
      throw new RuntimeException("DynamicTableRegistry.purgeForConnector: " + e.getMessage(), e);
    }
  }

  /**
   * What {@link #purgeForConnector} would take, read without taking it (#1218): {@code "schema.table"
   * -> rows}, by the purge's own {@link #plan}. No lock: a count, not a promise.
   */
  public Map<String, Long> rowsGoingWith(UUID connectorId) {
    return Jdbc.transaction(adminDs, "DynamicTableRegistry.rowsGoingWith", c -> {
      Map<String, Long> rows = new LinkedHashMap<>();
      try (PreparedStatement ps = c.prepareStatement("SELECT table_schema, table_name FROM connector.dynamic_table "
          + "WHERE connector_id = ? ORDER BY table_schema, table_name")) {
        ps.setObject(1, connectorId);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            String schema = rs.getString(1);
            String table = rs.getString(2);
            if (isHubDynamicTable(schema, table)) rows.put(schema + "." + table, rowsGoing(c, connectorId, schema, table));
          }
        }
      }
      return rows;
    });
  }

  private static long rowsGoing(Connection c, UUID connectorId, String schema, String table) throws SQLException {
    String rel = quoteIdent(schema) + "." + quoteIdent(table);
    if (!UnseenRowSweeper.exists(c, rel)) return 0;
    Plan plan = plan(c, connectorId, schema, table);
    if (!plan.drop() && plan.mine() == null) return 0;
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT count(*) FROM " + rel + (plan.drop() ? "" : " WHERE " + plan.mine()))) {
      if (!plan.drop()) ps.setObject(1, connectorId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  /**
   * A table's name carries neither its tenant nor its connector ({@code t_<name>}), so two
   * connectors — of one tenant or of two — can write into the same table (#1186). The table goes
   * only with its last writer: while another registration names it, or it holds a row this
   * connector did not write, only this connector's rows are deleted. A row is this connector's by
   * its lineage column: {@code _run_id} (a run of the connector) on a {@code t_*} table,
   * {@code connector_id} on a hybrid one. A table with neither is dropped once no registration
   * names it, as before.
   *
   * <p>Runs under {@link DynamicTableWriter}'s first-write lock on {@code schema.table}, taken
   * exclusively: a {@code t_*} table's first write holds it too, and a hybrid write holds it shared
   * for the whole write ({@link DynamicTableWriter#writeLock}), so no other connector registers or
   * writes the table between the check and the drop.
   *
   * @return whether the table was dropped
   */
  private static boolean dropIfLastWriter(Connection c, UUID connectorId, String schema, String table)
      throws SQLException {
    String rel = quoteIdent(schema) + "." + quoteIdent(table);
    DynamicTableWriter.firstWriteLock(c, schema, table);
    Plan plan = plan(c, connectorId, schema, table);
    if (plan.drop()) {
      try (Statement st = c.createStatement()) {
        st.execute("DROP TABLE IF EXISTS " + rel);
      }
    } else if (plan.mine() != null) {
      try (PreparedStatement del = c.prepareStatement("DELETE FROM " + rel + " WHERE " + plan.mine())) {
        del.setObject(1, connectorId);
        del.executeUpdate();
      }
    }
    return plan.drop();
  }

  /**
   * What a delete of {@code connectorId} does to one of its tables — the rule {@link #dropIfLastWriter}
   * applies and {@link #rowsGoingWith} counts: drop it, or keep it and delete the rows {@code mine}
   * picks (null: none). Kept while another connector's registration names it, or it holds a row this
   * connector did not write.
   */
  private record Plan(boolean drop, String mine) {}

  private static Plan plan(Connection c, UUID connectorId, String schema, String table) throws SQLException {
    String rel = quoteIdent(schema) + "." + quoteIdent(table);
    String mine = lineage(c, rel);
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT EXISTS (SELECT 1 FROM connector.dynamic_table WHERE table_schema = ? AND table_name = ? "
            + "AND connector_id IS DISTINCT FROM ?)"
            + (mine == null ? "" : " OR EXISTS (SELECT 1 FROM " + rel + " WHERE (" + mine + ") IS NOT TRUE)"))) {
      ps.setString(1, schema);
      ps.setString(2, table);
      ps.setObject(3, connectorId);
      if (mine != null) ps.setObject(4, connectorId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return new Plan(!rs.getBoolean(1), mine);
      }
    }
  }

  /**
   * The Search watermark of a table this tenant no longer writes goes in the same purge (#1188). Per
   * tenant: a {@code t_*} table is shared between tenants, and so is its name in this ledger.
   */
  private static void forgetWatermark(Connection c, long tenantId, String schema, String table) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "DELETE FROM core.search_projection_mark_watermark WHERE tenant_id = ? AND table_name = ? "
            + "AND NOT EXISTS (SELECT 1 FROM connector.dynamic_table "
            + "WHERE tenant_id = ? AND table_schema = ? AND table_name = ?)")) {
      ps.setLong(1, tenantId);
      ps.setString(2, schema + "." + table);
      ps.setLong(3, tenantId);
      ps.setString(4, schema);
      ps.setString(5, table);
      ps.executeUpdate();
    }
  }

  /** The predicate (one {@code ?}: the connector id) that picks a connector's rows in {@code rel}, or null. */
  private static String lineage(Connection c, String rel) throws SQLException {
    Set<String> columns = new HashSet<>();
    try (PreparedStatement ps = c.prepareStatement("SELECT attname FROM pg_catalog.pg_attribute "
        + "WHERE attrelid = to_regclass(?) AND attname IN ('_run_id', 'connector_id') AND NOT attisdropped")) {
      ps.setString(1, rel);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) columns.add(rs.getString(1));
      }
    }
    if (columns.contains("_run_id")) {
      return "_run_id IN (SELECT id FROM connector.connector_run WHERE connector_id = ?)";
    }
    return columns.contains("connector_id") ? "connector_id = ?" : null;
  }

  /** Upsert the schema registry entry, linking it to its owning connector (for scope discovery). */
  public void register(Long tenantId, String mappingId, String tableSchema, String tableName,
                       Map<String, String> schema, UUID connectorId) {
    try (Connection c = adminDs.getConnection()) {
      register(c, tenantId, mappingId, tableSchema, tableName, schema, connectorId);
    } catch (Exception e) {
      throw new RuntimeException("DynamicTableRegistry.register: " + e.getMessage(), e);
    }
  }

  /**
   * Same as {@link #register(Long, String, String, String, Map, UUID)}, on a caller-supplied
   * connection — so {@code DynamicTableWriter.createSchema} can write it inside the SAME
   * transaction that holds its advisory lock and creates the table, instead of checking out a
   * second connection from the pool while the first sits idle (round 3 review: that pattern
   * starves the admin pool under enough concurrent distinct-table creations).
   */
  public void register(Connection c, Long tenantId, String mappingId, String tableSchema, String tableName,
                       Map<String, String> schema, UUID connectorId) throws SQLException {
    String sql = "INSERT INTO connector.dynamic_table " +
                 "(tenant_id, mapping_id, table_schema, table_name, schema_inferred, connector_id) " +
                 "VALUES (?, ?, ?, ?, ?::jsonb, ?) " +
                 "ON CONFLICT (tenant_id, mapping_id, connector_id) DO UPDATE SET " +
                 "  schema_inferred = EXCLUDED.schema_inferred, " +
                 "  table_schema = EXCLUDED.table_schema, " +
                 "  table_name = EXCLUDED.table_name, " +
                 "  updated_at = now()";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setLong(1, tenantId);
      ps.setString(2, mappingId);
      ps.setString(3, tableSchema);
      ps.setString(4, tableName);
      ps.setString(5, M.writeValueAsString(schema));
      ps.setObject(6, connectorId);
      ps.executeUpdate();
    } catch (JsonProcessingException e) {
      throw new RuntimeException("DynamicTableRegistry.register: " + e.getMessage(), e);
    }
  }
}
