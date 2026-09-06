package io.lumnik.hub.connector.mapping.writer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ExtractionSink;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

  /** Returns the inferred schema {column: pg_type} or null if not registered. */
  public Map<String, String> findSchema(Long tenantId, String mappingId) {
    String sql = "SELECT schema_inferred::text FROM connector.dynamic_table " +
                 "WHERE tenant_id = ? AND mapping_id = ?";
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setLong(1, tenantId);
      ps.setString(2, mappingId);
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
   * Drops every dynamic table owned by the connector and deletes its registry rows,
   * atomically. Admin datasource (BYPASSRLS); connector_id is a globally-unique UUID
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
        for (String[] t : tables) {
          if (!isHubDynamicTable(t[0], t[1])) {
            LOG.warnf("purgeForConnector(%s): skipping unexpected registry table %s.%s "
                + "(neither %s.%s* nor %s.*); not dropping it", connectorId, t[0], t[1],
                DYNAMIC_SCHEMA, DYNAMIC_PREFIX, HYBRID_SCHEMA);
            continue;
          }
          try (Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + quoteIdent(t[0]) + "." + quoteIdent(t[1]));
          }
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
        for (String[] t : tables) {
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

  /** Upsert the schema registry entry, linking it to its owning connector (for scope discovery). */
  public void register(Long tenantId, String mappingId, String tableSchema, String tableName,
                       Map<String, String> schema, UUID connectorId) {
    String sql = "INSERT INTO connector.dynamic_table " +
                 "(tenant_id, mapping_id, table_schema, table_name, schema_inferred, connector_id) " +
                 "VALUES (?, ?, ?, ?, ?::jsonb, ?) " +
                 "ON CONFLICT (tenant_id, mapping_id) DO UPDATE SET " +
                 "  schema_inferred = EXCLUDED.schema_inferred, " +
                 "  table_schema = EXCLUDED.table_schema, " +
                 "  table_name = EXCLUDED.table_name, " +
                 "  connector_id = EXCLUDED.connector_id, " +
                 "  updated_at = now()";
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setLong(1, tenantId);
      ps.setString(2, mappingId);
      ps.setString(3, tableSchema);
      ps.setString(4, tableName);
      ps.setString(5, M.writeValueAsString(schema));
      ps.setObject(6, connectorId);
      ps.executeUpdate();
    } catch (Exception e) {
      throw new RuntimeException("DynamicTableRegistry.register: " + e.getMessage(), e);
    }
  }
}
