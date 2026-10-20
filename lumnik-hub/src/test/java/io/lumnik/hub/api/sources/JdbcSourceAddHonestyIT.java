package io.lumnik.hub.api.sources;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorCredential;
import io.lumnik.hub.connector.repository.ConnectorCredentialRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.testsupport.RivalConnectorWrite;
import io.lumnik.hub.webhook.SecretCipher;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * The guided flow ({@code lm source jdbc add}) must be as honest as {@code lm apply}: it either
 * creates a connector that can run, or refuses at add time, naming the table and the reason,
 * and leaves nothing behind. Found live against an ERP living in its own schema: a phantom
 * descriptor for a table looked up in the wrong schema, an invented primary key, a config
 * {@code lm validate} would refuse.
 */
@QuarkusTest
class JdbcSourceAddHonestyIT {

  static PostgreSQLContainer<?> PG;
  static String jdbcUrl;

  @Inject ConnectorRepository connectors;
  @Inject ConnectorCredentialRepository credentials;
  @Inject SecretCipher cipher;
  @Inject @DataSource("admin") AgroalDataSource adminDs;

  @BeforeAll
  static void startSourceDb() throws Exception {
    PG = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("source").withUsername("u").withPassword("p");
    PG.start();
    jdbcUrl = PG.getJdbcUrl();
    try (Connection c = DriverManager.getConnection(jdbcUrl, "u", "p"); var st = c.createStatement()) {
      st.execute("CREATE TABLE audit_log (seq BIGINT, msg TEXT)");                       // no primary key, no `id`
      st.execute("CREATE SCHEMA erp");
      st.execute("CREATE TABLE erp.orders (order_id BIGINT PRIMARY KEY, updated TIMESTAMP)");
      st.execute("CREATE TABLE ref_codes (code TEXT PRIMARY KEY, label TEXT)");         // nothing to watermark on
      st.execute("CREATE TABLE \"order-lines\" (id BIGINT PRIMARY KEY)");                // not a plain identifier
    }
  }

  @AfterAll
  static void stopSourceDb() {
    if (PG != null) PG.stop();
  }

  @Test
  void add_refuses_a_table_without_primary_key_instead_of_inventing_one() {
    given().headers(TestIdentity.tenantAndUser(1))
        .contentType(ContentType.JSON)
        .body(add("erp-nopk", Map.of("table", "audit_log")))
        .when().post("/api/platform/sources/jdbc/add")
        .then().statusCode(400)
              .body("error", containsString("audit_log"))
              .body("error", containsString("primary key"));

    given().headers(TestIdentity.tenantAndUser(1))
        .when().get("/api/platform/sources/jdbc/erp-nopk/tables")
        .then().statusCode(200)
              .body("tables.size()", is(0));
  }

  @Test
  void add_reads_the_table_from_the_declared_schema() {
    given().headers(TestIdentity.tenantAndUser(1))
        .contentType(ContentType.JSON)
        .body(add("erp-schema", "erp", Map.of("table", "orders", "watermark", "updated", "mode", "watermark")))
        .when().post("/api/platform/sources/jdbc/add")
        .then().statusCode(200)
              .body("created.size()", is(1));

    assertThat(config("erp-schema--orders")).containsEntry("table-schema", "erp");
  }

  @Test
  void add_writes_a_config_lm_validate_would_accept() {
    given().headers(TestIdentity.tenantAndUser(1))
        .contentType(ContentType.JSON)
        .body(add("erp-ref", Map.of("table", "ref_codes")))
        .when().post("/api/platform/sources/jdbc/add")
        .then().statusCode(200)
              .body("created.size()", is(1));

    Map<String, Object> cfg = config("erp-ref--ref_codes");
    assertThat(cfg).as("chunking is fixed at 500 — a manifest declaring it is refused").doesNotContainKey("chunk-size");
    assertThat(cfg).as("no watermark column exists, so the honest mode is full").containsEntry("mode", "full");
    assertThat(cfg).doesNotContainKey("watermark-column");
  }

  @Test
  void add_refuses_a_mode_it_does_not_know_and_treats_a_blank_one_as_absent() {
    given().headers(TestIdentity.tenantAndUser(1))
        .contentType(ContentType.JSON)
        .body(add("erp-mode", Map.of("table", "ref_codes", "mode", "foo")))
        .when().post("/api/platform/sources/jdbc/add")
        .then().statusCode(400)
              .body("error", containsString("foo"));

    given().headers(TestIdentity.tenantAndUser(1))
        .contentType(ContentType.JSON)
        .body(add("erp-mode", Map.of("table", "ref_codes", "mode", "")))
        .when().post("/api/platform/sources/jdbc/add")
        .then().statusCode(200);
    assertThat(config("erp-mode--ref_codes")).containsEntry("mode", "full");
  }

  /** #1193: the guided add creates connectors, so each one it creates leaves a row in the audit trail. */
  @Test
  void add_records_each_connector_it_creates_in_the_audit_trail() {
    given().headers(TestIdentity.tenantAndUser(1))
        .contentType(ContentType.JSON)
        .body(add("erp-audit", Map.of("table", "ref_codes")))
        .when().post("/api/platform/sources/jdbc/add")
        .then().statusCode(200)
              .body("created.size()", is(1));

    String key = connectorId("erp-audit--ref_codes").toString();
    long rows = Jdbc.queryOne(adminDs, "JdbcSourceAddHonestyIT.auditRows",
        "SELECT count(*) FROM core.audit_outbox "
            + "WHERE tenant_id = 1 AND entity_type = 'Connector' AND entity_key = ? AND operation = 'APPLY'",
        ps -> ps.setString(1, key), rs -> rs.getLong(1)).orElseThrow();
    assertThat(rows).isEqualTo(1);
  }

  @Test
  void a_refusal_leaves_nothing_behind_so_the_corrected_request_succeeds() {
    given().headers(TestIdentity.tenantAndUser(1))
        .contentType(ContentType.JSON)
        .body(add("erp-retry", Map.of("table", "ref_codes", "mode", "watermark")))   // nothing to watermark on
        .when().post("/api/platform/sources/jdbc/add")
        .then().statusCode(400)
              .body("error", containsString("ref_codes"));

    given().headers(TestIdentity.tenantAndUser(1))
        .contentType(ContentType.JSON)
        .body(add("erp-retry", Map.of("table", "ref_codes", "mode", "full")))        // the operator corrects
        .when().post("/api/platform/sources/jdbc/add")
        .then().statusCode(200)
              .body("created.size()", is(1));
  }

  @Test
  void a_refused_table_takes_its_whole_request_down() {
    given().headers(TestIdentity.tenantAndUser(1))
        .contentType(ContentType.JSON)
        .body(add("erp-pair", "erp", List.of(
            Map.of("table", "orders", "watermark", "updated", "mode", "watermark"),
            Map.of("table", "audit_log"))))                                            // second table has no pk
        .when().post("/api/platform/sources/jdbc/add")
        .then().statusCode(400);

    given().headers(TestIdentity.tenantAndUser(1))
        .when().get("/api/platform/sources/jdbc/erp-pair/tables")
        .then().statusCode(200)
              .body("tables.size()", is(0));
  }

  /** The source's password rotates; the operator re-runs add with the new one. The stored
   *  credential — what every later run authenticates with — must follow, or the add succeeds
   *  (it introspects with the request's password) while the next run fails to log in. */
  @Test
  void a_second_add_with_a_new_password_updates_the_stored_credential() throws Exception {
    given().headers(TestIdentity.tenantAndUser(1)).contentType(ContentType.JSON)
        .body(add("erp-rotate", "erp", Map.of("table", "orders")))
        .when().post("/api/platform/sources/jdbc/add").then().statusCode(200);
    assertThat(storedSecret("erp-rotate--credential")).isEqualTo("u:p");

    try (Connection c = DriverManager.getConnection(jdbcUrl, "u", "p"); var st = c.createStatement()) {
      st.execute("ALTER USER u PASSWORD 'p2'");
    }
    try {
      Map<String, Object> body = add("erp-rotate", null, List.of(Map.of("table", "ref_codes")));
      body.put("password", "p2");
      given().headers(TestIdentity.tenantAndUser(1)).contentType(ContentType.JSON)
          .body(body)
          .when().post("/api/platform/sources/jdbc/add").then().statusCode(200);
      assertThat(storedSecret("erp-rotate--credential")).isEqualTo("u:p2");
    } finally {
      try (Connection c = DriverManager.getConnection(jdbcUrl, "u", "p2"); var st = c.createStatement()) {
        st.execute("ALTER USER u PASSWORD 'p'");
      }
    }
  }

  @Test
  void add_refuses_a_table_whose_name_the_runtime_would_refuse() {
    given().headers(TestIdentity.tenantAndUser(1)).contentType(ContentType.JSON)
        .body(add("erp-ident", Map.of("table", "order-lines")))
        .when().post("/api/platform/sources/jdbc/add")
        .then().statusCode(400)
              .body("error", containsString("order-lines"))
              .body("error", containsString("identifier"));
  }

  @Test
  void add_refuses_a_watermark_column_the_table_does_not_have() {
    given().headers(TestIdentity.tenantAndUser(1)).contentType(ContentType.JSON)
        .body(add("erp-wmcol", "erp", Map.of("table", "orders", "watermark", "nope", "mode", "watermark")))
        .when().post("/api/platform/sources/jdbc/add")
        .then().statusCode(400)
              .body("error", containsString("nope"));
  }

  /** The runtime quotes identifiers, so the persisted column must be spelled as the catalog spells it. */
  @Test
  void add_persists_the_watermark_column_as_the_catalog_spells_it() {
    given().headers(TestIdentity.tenantAndUser(1)).contentType(ContentType.JSON)
        .body(add("erp-wmcase", "erp", Map.of("table", "orders", "watermark", "UPDATED", "mode", "watermark")))
        .when().post("/api/platform/sources/jdbc/add")
        .then().statusCode(200);
    assertThat(config("erp-wmcase--orders")).containsEntry("watermark-column", "updated");
  }

  /**
   * The operator deletes a source's connector and adds it again — the ordinary way a bad first
   * guess is corrected. Found live: the second add answered an anonymous 500. The first add had
   * also written a {@code connector.connector_mapping} row named after the connector, {@code
   * connector delete} does not remove it (the {@code mapping_id} link is a soft ref by design),
   * and the row's {@code UNIQUE (tenant_id, name)} then refuses the second add. The violation
   * surfaces at COMMIT, after the resource returned 200, so none of the door's named refusals
   * could word it — and no command could remove the orphan either.
   */
  @Test
  void re_adding_a_deleted_source_is_not_blocked_by_what_the_first_add_left_behind() {
    given().headers(TestIdentity.tenantAndUser(1)).contentType(ContentType.JSON)
        .body(add("erp-readd", "erp", Map.of("table", "orders")))
        .when().post("/api/platform/sources/jdbc/add")
        .then().statusCode(200).body("created.size()", is(1));

    given().headers(TestIdentity.tenantAndUser(1))
        .when().delete("/api/platform/connectors/" + connectorId("erp-readd--orders"))
        .then().statusCode(204);

    given().headers(TestIdentity.tenantAndUser(1)).contentType(ContentType.JSON)
        .body(add("erp-readd", "erp", Map.of("table", "orders")))
        .when().post("/api/platform/sources/jdbc/add")
        .then().statusCode(200).body("created.size()", is(1));
  }

  /**
   * Reads one field off the named connector, in its own transaction. The lookup is asserted rather
   * than dereferenced: every caller has just been told the add created it, so an absent row is the
   * finding — and a bare NPE from a helper names neither the connector nor the claim that failed.
   */
  private <T> T onConnector(String connectorName, Function<Connector, T> read) {
    return TenantContext.callAsSystem(() -> QuarkusTransaction.requiringNew().call(() -> {
      Connector c = connectors.find("name = ?1 AND tenantId = ?2", connectorName, 1L).firstResult();
      assertThat(c).as("the add reported success but persisted no connector '%s'", connectorName).isNotNull();
      return read.apply(c);
    }));
  }

  private UUID connectorId(String connectorName) {
    return onConnector(connectorName, Connector::getId);
  }

  private String storedSecret(String credentialName) {
    return TenantContext.callAsSystem(() -> QuarkusTransaction.requiringNew().call(() -> {
      ConnectorCredential cred =
          credentials.find("name = ?1 AND tenantId = ?2", credentialName, 1L).firstResult();
      assertThat(cred).as("no credential '%s' was persisted", credentialName).isNotNull();
      return cipher.decrypt(cred.getCiphertext());
    }));
  }

  /**
   * #1231: a connector created under the table's name while the add introspects — after its
   * unlocked look for existing connectors — is the one the add skips, not a constraint's 500.
   */
  @Test
  void a_connector_created_under_the_name_while_the_add_runs_is_skipped_not_a_500() throws Exception {
    UUID holder = UUID.randomUUID();
    try (var rival = new RivalConnectorWrite(adminDs, holder, "erp-race--ref_codes")) {
      rival.answerAfterCommit(() -> given().headers(TestIdentity.tenantAndUser(1))
              .contentType(ContentType.JSON)
              .body(add("erp-race", Map.of("table", "ref_codes")))
              .when().post("/api/platform/sources/jdbc/add"))
          .then().statusCode(200)
                .body("created.size()", is(0))
                .body("skipped[0].connectorId", is(holder.toString()));
    }
  }

  /** #1244: the schedule is checked like the manifest's — a malformed one never fires, so it is refused. */
  @Test
  void add_refuses_a_schedule_that_is_not_a_cron_and_stores_a_valid_one() {
    Map<String, Object> bad = add("erp-cron", Map.of("table", "ref_codes"));
    bad.put("scheduleCron", "not a cron");
    given().headers(TestIdentity.tenantAndUser(1))
        .contentType(ContentType.JSON).body(bad)
        .when().post("/api/platform/sources/jdbc/add")
        .then().statusCode(400)
              .body("error", containsString("not a cron"));

    given().headers(TestIdentity.tenantAndUser(1))
        .when().get("/api/platform/sources/jdbc/erp-cron/tables")
        .then().statusCode(200)
              .body("tables.size()", is(0));

    Map<String, Object> good = add("erp-cron", Map.of("table", "ref_codes"));
    good.put("scheduleCron", "0 6 * * *");
    given().headers(TestIdentity.tenantAndUser(1))
        .contentType(ContentType.JSON).body(good)
        .when().post("/api/platform/sources/jdbc/add")
        .then().statusCode(200);
    assertThat(onConnector("erp-cron--ref_codes", Connector::getSchedule)).isEqualTo("0 6 * * *");
  }

  private static Map<String, Object> add(String source, Map<String, String> table) {
    return add(source, null, List.of(table));
  }

  private static Map<String, Object> add(String source, String schema, Map<String, String> table) {
    return add(source, schema, List.of(table));
  }

  /** The request body of one add; {@code schema} absent when null (the hub defaults to public). */
  private static Map<String, Object> add(String source, String schema, List<Map<String, String>> tables) {
    Map<String, Object> body = new HashMap<>(Map.of("sourceName", source, "url", jdbcUrl, "user", "u",
        "password", "p", "tables", tables));
    if (schema != null) body.put("schema", schema);
    return body;
  }

  private Map<String, Object> config(String connectorName) {
    return onConnector(connectorName, Connector::getConfig);
  }
}
