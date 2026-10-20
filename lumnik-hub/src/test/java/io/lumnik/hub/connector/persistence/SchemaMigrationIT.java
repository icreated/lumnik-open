package io.lumnik.hub.connector.persistence;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class SchemaMigrationIT {

  @Inject DataSource ds;

  @Test
  void connector_schema_has_expected_core_tables() throws Exception {
    // Excludes dynamic ingestion tables (connector.t_*), which other ITs create and leave behind
    // (test-order-dependent). Asserts the migration-created core tables are present rather than an
    // exact set, so adding a new platform table doesn't break this test.
    try (Connection c = ds.getConnection();
         var stmt = c.createStatement();
         ResultSet rs = stmt.executeQuery(
             "SELECT table_name FROM information_schema.tables WHERE table_schema='connector' "
             + "AND table_name NOT LIKE 't\\_%' ORDER BY table_name")) {
      var found = new ArrayList<String>();
      while (rs.next()) found.add(rs.getString(1));
      assertThat(found).contains(
          "connector", "connector_credential", "connector_cursor", "connector_dead_letter",
          "connector_endpoint", "connector_run",
          "dynamic_table", "schema_card");
      // cursor_state was dropped in V23 (legacy REST direct path retired), connector_mapping in V70
      // (the mapping override storage, #1174)
      assertThat(found).doesNotContain("cursor_state", "connector_mapping");
    }
  }

  @Test
  void rls_enabled_on_connector_table() throws Exception {
    var tables = List.of(
        "connector.connector",
        "connector.connector_cursor",
        "connector.connector_run",
        "connector.connector_credential",
        "connector.connector_dead_letter"
    );
    try (Connection c = ds.getConnection();
         var stmt = c.createStatement()) {
      for (String table : tables) {
        ResultSet rs = stmt.executeQuery(
            "SELECT relrowsecurity FROM pg_class WHERE oid = '" + table + "'::regclass");
        assertThat(rs.next()).as("table " + table + " not found in pg_class").isTrue();
        assertThat(rs.getBoolean(1)).as("RLS should be enabled on " + table).isTrue();
        rs.close();
      }
    }
  }
}
