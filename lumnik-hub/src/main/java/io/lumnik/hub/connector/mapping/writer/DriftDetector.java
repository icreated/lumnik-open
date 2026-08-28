package io.lumnik.hub.connector.mapping.writer;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
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
