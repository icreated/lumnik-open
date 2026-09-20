package io.lumnik.hub.connector.mapping.writer;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects schema drift between expected (registry-recorded) and actual (live DB) column types.
 * Warn-only: returns warning messages, does not block.
 */
@ApplicationScoped
public class DriftDetector {

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  public List<String> detect(String tableSchema, String tableName, Map<String, String> expectedSchema) {
    List<String> warnings = new ArrayList<>();
    Map<String, String> actual = queryActualSchema(tableSchema, tableName);
    for (var e : expectedSchema.entrySet()) {
      String expectedType = e.getValue();
      String actualType = actual.get(e.getKey());
      if (actualType == null) {
        warnings.add("column " + e.getKey() + ": expected " + expectedType
            + " but missing in " + tableSchema + "." + tableName);
      } else if (!compatible(expectedType, actualType)) {
        warnings.add("column " + e.getKey() + ": expected " + expectedType + " but actual " + actualType);
      }
    }
    return warnings;
  }

  /**
   * The table's physical column types, translated into the DDL vocabulary {@link SchemaInferrer}
   * emits ({@code BIGINT}, {@code NUMERIC(18,4)}, {@code TEXT}, ...) rather than {@code
   * information_schema}'s own spelling, so a caller can register or compare it exactly as an
   * inferred schema would be. {@code null} when the table does not exist at all; a (possibly
   * empty) map otherwise.
   *
   * <p>This is the physical truth {@code DynamicTableWriter.createSchema} reconciles against
   * instead of trusting a chunk's own fresh inference when {@code CREATE TABLE IF NOT EXISTS}
   * turns out to be a no-op against a table a sibling mapping (or an earlier, since-deleted
   * registry row) already created (#525).
   */
  public Map<String, String> actualSchema(String tableSchema, String tableName) {
    try (Connection c = adminDs.getConnection()) {
      return actualSchema(c, tableSchema, tableName);
    } catch (SQLException e) {
      throw new RuntimeException("DriftDetector.actualSchema: " + e.getMessage(), e);
    }
  }

  /**
   * Same as {@link #actualSchema(String, String)}, on a caller-supplied connection — so
   * {@code DynamicTableWriter.createSchema} can read it inside the SAME transaction that holds
   * its advisory lock, instead of checking out a second connection from the pool while the first
   * sits idle (round 3 review: that pattern starves the admin pool under enough concurrent
   * distinct-table creations).
   */
  public Map<String, String> actualSchema(Connection c, String tableSchema, String tableName) throws SQLException {
    Map<String, String> out = new LinkedHashMap<>();
    boolean exists = false;
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT column_name, data_type FROM information_schema.columns " +
        "WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position")) {
      ps.setString(1, tableSchema);
      ps.setString(2, tableName);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          exists = true;
          out.put(rs.getString("column_name"), toDdlType(rs.getString("data_type")));
        }
      }
    }
    return exists ? out : null;
  }

  /** {@code information_schema.columns.data_type} → the DDL string {@link SchemaInferrer} emits
   *  for that same kind of value, so a physical column reads exactly as an inferred one would. */
  private static String toDdlType(String dataType) {
    String d = dataType.toLowerCase();
    if (d.contains("timestamp")) return "TIMESTAMPTZ";
    if (d.equals("date")) return "DATE";
    if (d.equals("boolean")) return "BOOLEAN";
    if (d.contains("json")) return "JSONB";
    if (d.contains("numeric") || d.contains("decimal") || d.contains("double") || d.contains("real")) {
      return SchemaInferrer.NUMERIC;
    }
    if (d.contains("int")) return SchemaInferrer.BIGINT;
    return "TEXT";
  }

  private Map<String, String> queryActualSchema(String schema, String name) {
    Map<String, String> out = new HashMap<>();
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(
             "SELECT column_name, data_type FROM information_schema.columns " +
             "WHERE table_schema = ? AND table_name = ?")) {
      ps.setString(1, schema);
      ps.setString(2, name);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.put(rs.getString(1), rs.getString(2).toUpperCase());
        }
      }
    } catch (Exception e) {
      // Best-effort: if DB is unreachable, return empty (no warnings)
    }
    return out;
  }

  private boolean compatible(String expected, String actual) {
    String e = expected.toUpperCase().replaceAll("\\(.*\\)", "");
    String a = actual.toUpperCase();
    if (e.equals(a)) return true;
    if (e.equals("TIMESTAMPTZ") && a.contains("TIMESTAMP")) return true;
    if (e.equals("NUMERIC") && (a.contains("NUMERIC") || a.contains("DECIMAL"))) return true;
    if (e.equals("TEXT") && (a.contains("TEXT") || a.contains("CHARACTER"))) return true;
    if (e.equals("BIGINT") && (a.contains("BIGINT") || a.contains("INTEGER"))) return true;
    return false;
  }
}
