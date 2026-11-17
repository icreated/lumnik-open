package io.lumnik.hub.testsupport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.connector.mapping.writer.DriftDetector;
import io.lumnik.hub.connector.mapping.writer.DynamicTableWriter;
import io.quarkus.arc.Arc;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A hub table a test made by hand, recorded for a tenant as the hub's writer records one (#2023): an Entity a tenant
 * applies names only the columns its own writers recorded. Every column the table holds, the system ones aside, goes to
 * the tenant's registrations of it — those it has, or one made here.
 */
public final class RegisteredTables {

  private RegisteredTables() {
  }

  /**
   * Every business column the table holds, for {@code tenant}: right only when this tenant made all of them. In a test of
   * a table two tenants share, name its own with {@link #register(DataSource, long, String, List)}.
   */
  public static void register(DataSource admin, long tenant, String table) {
    register(admin, tenant, table, null);
  }

  /** Only {@code own} of the table's columns, typed as the table holds them: what this tenant's data brought. */
  public static void register(DataSource admin, long tenant, String table, List<String> own) {
    // the writer's own vocabulary (BIGINT, TIMESTAMPTZ…), as it registers a table it made
    Map<String, String> made = Arc.container().instance(DriftDetector.class).get().actualSchema("connector", table);
    if (made == null) throw new IllegalStateException("connector." + table + " is not made yet");
    Map<String, String> columns = new LinkedHashMap<>(made);
    columns.keySet().removeAll(DynamicTableWriter.SYSTEM_COLUMNS);
    if (own != null) columns.keySet().retainAll(own);
    String json = toJson(columns);
    int updated = Jdbc.update(admin, "RegisteredTables.update", "UPDATE connector.dynamic_table "
        + "SET schema_inferred = schema_inferred || ?::jsonb WHERE tenant_id = ? AND table_schema = 'connector' "
        + "AND table_name = ?", ps -> {
          ps.setString(1, json);
          ps.setLong(2, tenant);
          ps.setString(3, table);
        });
    if (updated > 0) return;
    Jdbc.update(admin, "RegisteredTables.insert", "INSERT INTO connector.dynamic_table "
        + "(tenant_id, mapping_id, table_schema, table_name, schema_inferred) VALUES (?, ?, 'connector', ?, ?::jsonb)",
        ps -> {
          ps.setLong(1, tenant);
          ps.setString(2, "test:" + table);
          ps.setString(3, table);
          ps.setString(4, json);
        });
  }

  /** Each of {@code tenants}. */
  public static void register(DataSource admin, String table, List<Long> tenants) {
    tenants.forEach(t -> register(admin, t, table));
  }

  private static String toJson(Map<String, String> columns) {
    try {
      return new ObjectMapper().writeValueAsString(columns);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

}
