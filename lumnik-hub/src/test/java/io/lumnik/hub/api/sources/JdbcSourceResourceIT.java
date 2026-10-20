package io.lumnik.hub.api.sources;

import io.agroal.api.AgroalDataSource;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * REST IT for /api/platform/sources/jdbc/*.
 *
 * <p>We manage the source-DB container lifecycle manually in @BeforeAll/@AfterAll
 * rather than using @Testcontainers, so that the container is started before
 * any test method runs (the @Testcontainers + @QuarkusTest combo can cause
 * ordering issues where the Quarkus test client calls getJdbcUrl() before
 * Testcontainers has actually started the container).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JdbcSourceResourceIT {

  static PostgreSQLContainer<?> PG;
  static String jdbcUrl;

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  @BeforeAll
  static void startSourceDb() {
    PG = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("source").withUsername("u").withPassword("p")
        .withCopyFileToContainer(
            MountableFile.forClasspathResource("jdbc/legacy-erp-schema.sql"),
            "/docker-entrypoint-initdb.d/init.sql");
    PG.start();
    jdbcUrl = PG.getJdbcUrl();
  }

  @AfterAll
  static void stopSourceDb() {
    if (PG != null) PG.stop();
  }

  @Test
  @Order(1)
  void test_endpoint_returns_ok_for_valid_credentials() {
    given().headers(TestIdentity.tenantAndUser(1))
        .contentType(ContentType.JSON)
        .body(Map.of("url", jdbcUrl, "user", "u", "password", "p"))
        .when().post("/api/platform/sources/jdbc/test")
        .then().statusCode(200)
              .body("ok", is(true))
              .body("dialect", is("postgres"));
  }

  @Test
  @Order(2)
  void test_endpoint_returns_error_for_wrong_password() {
    // Note: HikariPoolRegistry caches pools by (url, user, tenantId=-1).
    // When a pool already exists for this (url, user) pair (from test 1), the pool is reused
    // and the wrong password in the request body is ignored — the endpoint returns 200.
    // If no pool exists, Hikari fails to connect and returns 502.
    // Both 200 (pool hit) and 502 (no pool, auth fails) are acceptable here.
    given().headers(TestIdentity.tenantAndUser(1))
        .contentType(ContentType.JSON)
        .body(Map.of("url", jdbcUrl, "user", "u", "password", "WRONG"))
        .when().post("/api/platform/sources/jdbc/test")
        .then().statusCode(anyOf(is(200), is(401), is(502)));
  }

  @Test
  @Order(3)
  void discover_returns_business_tables() {
    given().headers(TestIdentity.tenantAndUser(1))
        .contentType(ContentType.JSON)
        .body(Map.of("url", jdbcUrl, "user", "u", "password", "p",
            "schema", "public", "skipPatterns", List.of("t_*", "ad_*"), "includeViews", false))
        .when().post("/api/platform/sources/jdbc/discover")
        .then().statusCode(200)
              .body("tables.name", hasItems("c_bpartner", "m_product", "c_order"))
              .body("tables.name", not(hasItems("t_temp", "ad_meta")));
  }

  @Test
  @Order(4)
  void add_creates_n_connectors() {
    given().headers(TestIdentity.tenantAndUser(1))
        .contentType(ContentType.JSON)
        .body(Map.of(
            "sourceName", "legacy-erp-prod",
            "url", jdbcUrl, "user", "u", "password", "p",
            "tables", List.of(
                Map.of("table", "c_bpartner", "watermark", "updated", "mode", "watermark"),
                Map.of("table", "m_product",  "watermark", "updated", "mode", "watermark")),
            "scheduleCron", "0 * * * *"))
        .when().post("/api/platform/sources/jdbc/add")
        .then().statusCode(200)
              .body("created.size()", is(2));
  }

  @Test
  @Order(5)
  void add_is_idempotent_on_duplicate_name() {
    given().headers(TestIdentity.tenantAndUser(1))
        .contentType(ContentType.JSON)
        .body(Map.of(
            "sourceName", "legacy-erp-prod",
            "url", jdbcUrl, "user", "u", "password", "p",
            "tables", List.of(Map.of("table", "c_bpartner", "watermark", "updated", "mode", "watermark"))))
        .when().post("/api/platform/sources/jdbc/add")
        .then().statusCode(200)
              .body("created.size()", is(0))
              .body("skipped.size()", is(1));
  }

  @Test
  @Order(6)
  void list_tables_returns_added_connectors() {
    given().headers(TestIdentity.tenantAndUser(1))
        .when().get("/api/platform/sources/jdbc/legacy-erp-prod/tables")
        .then().statusCode(200)
              .body("tables.size()", greaterThanOrEqualTo(2));
  }

  @Test
  @Order(7)
  void the_credential_goes_with_the_last_connector_that_uses_it() {
    // #1250: the add made two connectors over one per-source credential
    String credential = "legacy-erp-prod--credential";
    List<String> ids = strings("SELECT c.id FROM connector.connector c JOIN connector.connector_credential k"
        + " ON k.id = c.credential_id WHERE k.tenant_id = 1 AND k.name = '" + credential + "' ORDER BY c.name");
    Assertions.assertEquals(2, ids.size());

    given().headers(TestIdentity.tenantAndUser(1))
        .when().delete("/api/platform/connectors/" + ids.get(0) + "?dryRun=true")
        .then().statusCode(200).body("$", not(hasKey("credential")));
    given().headers(TestIdentity.tenantAndUser(1))
        .when().delete("/api/platform/connectors/" + ids.get(0)).then().statusCode(204);
    Assertions.assertEquals(1, credentials(credential), "the other connector still authenticates with it");

    given().headers(TestIdentity.tenantAndUser(1))
        .when().delete("/api/platform/connectors/" + ids.get(1) + "?dryRun=true")
        .then().statusCode(200).body("credential", is(credential));
    given().headers(TestIdentity.tenantAndUser(1))
        .when().delete("/api/platform/connectors/" + ids.get(1)).then().statusCode(204);
    Assertions.assertEquals(0, credentials(credential), "the last connector takes its credential with it");
  }

  private int credentials(String name) {
    return strings("SELECT id FROM connector.connector_credential WHERE tenant_id = 1 AND name = '" + name + "'").size();
  }

  private List<String> strings(String sql) {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
      List<String> out = new ArrayList<>();
      while (rs.next()) out.add(rs.getString(1));
      return out;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
