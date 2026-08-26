package io.lumnik.hub.connector.mapping.writer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
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
  @io.quarkus.agroal.DataSource("admin")
  DataSource adminDs;

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
            + "RETURNING table_schema, table_name")) {
          del.setObject(1, connectorId);
          try (ResultSet rs = del.executeQuery()) {
            while (rs.next()) tables.add(new String[]{rs.getString(1), rs.getString(2)});
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
