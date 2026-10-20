package io.lumnik.hub.connector.mapping.writer;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1186: V72 registers every connector already writing a shared table, found from its rows. Flyway
 * ran it on an empty registry before the test boots, so the test seeds the pre-V71 state — two
 * connectors writing one table, only the first registered — and replays the migration's own SQL.
 * Each case runs in one transaction rolled back after it: V72 reads every registered table and
 * rewrites every stored card, and must not touch what the other tests left in the database.
 */
@QuarkusTest
class DynamicTableBackfillMigrationIT {

  private static final String V72 = "db/migration/connector/V72__dynamic_table_backfill_writers.sql";

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  private final UUID first = UUID.randomUUID();
  private final UUID second = UUID.randomUUID();

  private Connection tx;

  @BeforeEach
  void begin() throws Exception {
    tx = adminDs.getConnection();
    tx.setAutoCommit(false);
  }

  @AfterEach
  void rollback() throws Exception {
    tx.rollback();
    tx.setAutoCommit(true);
    tx.close();
  }

  @Test
  void a_writer_with_runs_in_a_t_table_gets_its_registration() throws Exception {
    connectors();
    UUID run1 = UUID.randomUUID(), run2 = UUID.randomUUID();
    exec(run(run1, first), run(run2, second),
        "CREATE TABLE connector.t_backfill1186 (id bigserial, tenant_id bigint NOT NULL, _source text, _run_id uuid, code text)",
        "INSERT INTO connector.t_backfill1186 (tenant_id, _source, _run_id, code) VALUES "
            + "(1, 'jdbc:backfill1186', '" + run1 + "', 'A'), (1, 'jdbc:backfill1186', '" + run2 + "', 'B')",
        registration("jdbc:backfill1186", "connector", "t_backfill1186", first));

    replayV72();
    replayV72();

    assertThat(registrations("t_backfill1186")).isEqualTo(first + "," + second);
  }

  @Test
  void a_stored_card_drops_a_table_another_scopes_connector_also_writes() throws Exception {
    a_writer_with_runs_in_a_t_table_gets_its_registration();
    exec("INSERT INTO connector.schema_card (tenant_id, scope, source_type, card) VALUES (1, 'a1186', 'hub', "
        + "'{\"scope\":\"a1186\",\"tables\":[{\"name\":\"connector.t_backfill1186\"},{\"name\":\"connector.v_kept1186\"}]}')");

    replayV72();

    try (Statement st = tx.createStatement();
         ResultSet rs = st.executeQuery("SELECT card -> 'tables' ->> 0, jsonb_array_length(card -> 'tables') "
             + "FROM connector.schema_card WHERE tenant_id = 1 AND scope = 'a1186'")) {
      rs.next();
      assertThat(rs.getString(1) + "|" + rs.getInt(2)).isEqualTo("{\"name\": \"connector.v_kept1186\"}|1");
    }
  }

  @Test
  void a_writer_of_a_hybrid_table_gets_its_registration() throws Exception {
    connectors();
    exec("CREATE SCHEMA IF NOT EXISTS ext",
        "CREATE TABLE ext.backfill1186 (id bigserial, tenant_id bigint NOT NULL, connector_id uuid NOT NULL, v text)",
        "INSERT INTO ext.backfill1186 (tenant_id, connector_id, v) VALUES (1, '" + first + "', 'a'), (1, '"
            + second + "', 'b')",
        registration("hybrid:ext.backfill1186", "ext", "backfill1186", first));

    replayV72();

    assertThat(registrations("backfill1186")).isEqualTo(first + "," + second);
  }

  private void connectors() throws Exception {
    for (UUID id : new UUID[] {first, second}) {
      exec("INSERT INTO connector.connector (id, tenant_id, name, type_id, config, enabled, tags) VALUES ('" + id
          + "', 1, 'backfill-" + id + "', 'csv-file', '{}'::jsonb, true, ARRAY['" + (id.equals(first) ? "a1186" : "b1186")
          + "'])");
    }
  }

  private static String run(UUID run, UUID connector) {
    return "INSERT INTO connector.connector_run (id, tenant_id, connector_id, status, sync_mode, triggered_by) VALUES ('"
        + run + "', 1, '" + connector + "', 'Completed', 'Incremental', 'Manual')";
  }

  private static String registration(String mapping, String schema, String table, UUID connector) {
    return "INSERT INTO connector.dynamic_table (tenant_id, mapping_id, table_schema, table_name, schema_inferred, connector_id) "
        + "VALUES (1, '" + mapping + "', '" + schema + "', '" + table + "', '{}', '" + connector + "')";
  }

  /** The connectors registered for {@code table}, in the order the test created them. */
  private String registrations(String table) throws Exception {
    try (Statement st = tx.createStatement();
         ResultSet rs = st.executeQuery("SELECT string_agg(connector_id::text, ',' ORDER BY connector_id = '"
             + second + "') FROM connector.dynamic_table WHERE table_name = '" + table + "'")) {
      rs.next();
      return rs.getString(1);
    }
  }

  private void replayV72() throws Exception {
    try (InputStream in = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(V72),
        V72 + " is not on the test classpath")) {
      exec(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  private void exec(String... sql) throws Exception {
    try (Statement st = tx.createStatement()) {
      for (String s : sql) st.execute(s);
    }
  }
}
