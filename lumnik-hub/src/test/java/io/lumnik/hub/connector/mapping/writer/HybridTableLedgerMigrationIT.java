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
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #2010: V98 records the hybrid tables made before the hub kept a record of them. Flyway ran it before the test boots,
 * so the test makes the pre-V98 tables and replays the migration's own SQL, twice. Each case runs in one transaction
 * rolled back after it.
 */
@QuarkusTest
class HybridTableLedgerMigrationIT {

  private static final String V98 = "db/migration/connector/V98__hybrid_table_ledger.sql";

  /** The columns and key {@code HybridJsonbWriter.createTableSql} gave every hybrid table before #2010. */
  private static String hybridTable(String rel) {
    return HybridJsonbWriter.createTableSql(rel, List.of());
  }

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

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

  /** The writer no longer creates ext: V98 does, with the USAGE no migration granted before. */
  @Test
  void ext_is_made_with_its_usage() throws Exception {
    try (Statement st = tx.createStatement();
         ResultSet rs = st.executeQuery("SELECT has_schema_privilege('lumnik_app', 'ext', 'USAGE')")) {
      rs.next();
      assertThat(rs.getBoolean(1)).isTrue();
    }
  }

  @Test
  void a_hybrid_table_under_ext_is_recorded() throws Exception {
    exec(hybridTable("ext.ledger2010"));

    replayV98();
    replayV98();

    assertThat(recorded("ext", "ledger2010")).isTrue();
  }

  @Test
  void a_hybrid_table_under_connector_is_recorded_when_a_hybrid_registration_names_it() throws Exception {
    exec(hybridTable("connector.t_ledger2010"), hybridTable("connector.t_unnamed2010"),
        "INSERT INTO connector.dynamic_table (tenant_id, mapping_id, table_schema, table_name, schema_inferred) "
            + "VALUES (1, 'hybrid:connector.t_ledger2010', 'connector', 't_ledger2010', '{}')");

    replayV98();

    assertThat(recorded("connector", "t_ledger2010")).isTrue();
    assertThat(recorded("connector", "t_unnamed2010")).as("no registration names it").isFalse();
  }

  /** Postgres keeps a name's first 63 characters; the registration keeps the target's whole name. */
  @Test
  void a_long_named_hybrid_table_under_connector_is_recorded() throws Exception {
    String target = "t_ledger2010_" + "x".repeat(60);
    exec(hybridTable("connector." + target),
        "INSERT INTO connector.dynamic_table (tenant_id, mapping_id, table_schema, table_name, schema_inferred) "
            + "VALUES (1, 'hybrid:connector." + target + "', 'connector', '" + target + "', '{}')");

    replayV98();

    assertThat(recorded("connector", target)).isTrue();
  }

  @Test
  void a_table_without_the_writers_id_is_not_recorded() throws Exception {
    exec("CREATE TABLE ext.idless2010 (tenant_id bigint, external_id text, data jsonb, connector_id uuid, "
        + "endpoint_id uuid, UNIQUE (tenant_id, external_id))");

    replayV98();

    assertThat(recorded("ext", "idless2010")).isFalse();
  }

  @Test
  void a_table_without_the_hybrid_key_is_not_recorded() throws Exception {
    exec("CREATE TABLE ext.keyless2010 (id bigint, tenant_id bigint, external_id text, data jsonb, connector_id uuid, "
            + "endpoint_id uuid)",
        "CREATE UNIQUE INDEX keyless2010_k ON ext.keyless2010 (tenant_id, external_id)");

    replayV98();

    assertThat(recorded("ext", "keyless2010")).isFalse();
  }

  private boolean recorded(String schema, String table) {
    return DynamicTableRegistry.ownsHybridTable(tx, schema, table);
  }

  private void replayV98() throws Exception {
    try (InputStream in = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(V98),
        V98 + " is not on the test classpath")) {
      exec(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  private void exec(String... sql) throws Exception {
    try (Statement st = tx.createStatement()) {
      for (String s : sql) st.execute(s);
    }
  }
}
