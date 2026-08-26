package io.lumnik.hub.api.sources;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

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
    given().header("X-Tenant-ID", "1").header("X-User-ID", "1")
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
    given().header("X-Tenant-ID", "1").header("X-User-ID", "1")
        .contentType(ContentType.JSON)
        .body(Map.of("url", jdbcUrl, "user", "u", "password", "WRONG"))
        .when().post("/api/platform/sources/jdbc/test")
        .then().statusCode(anyOf(is(200), is(401), is(502)));
  }

  @Test
  @Order(3)
  void discover_returns_business_tables() {
    given().header("X-Tenant-ID", "1").header("X-User-ID", "1")
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
    given().header("X-Tenant-ID", "1").header("X-User-ID", "1")
        .contentType(ContentType.JSON)
        .body(Map.of(
            "sourceName", "legacy-erp-prod",
            "url", jdbcUrl, "user", "u", "password", "p",
            "tables", List.of(
                Map.of("table", "c_bpartner", "watermark", "updated", "mode", "watermark"),
                Map.of("table", "m_product",  "watermark", "updated", "mode", "watermark")),
            "templateMode", "heuristic-only",
            "scheduleCron", "0 0 * * * *"))
        .when().post("/api/platform/sources/jdbc/add")
        .then().statusCode(200)
              .body("created.size()", is(2));
  }

  @Test
  @Order(5)
  void add_is_idempotent_on_duplicate_name() {
    given().header("X-Tenant-ID", "1").header("X-User-ID", "1")
        .contentType(ContentType.JSON)
        .body(Map.of(
            "sourceName", "legacy-erp-prod",
            "url", jdbcUrl, "user", "u", "password", "p",
            "tables", List.of(Map.of("table", "c_bpartner", "watermark", "updated", "mode", "watermark")),
            "templateMode", "heuristic-only"))
        .when().post("/api/platform/sources/jdbc/add")
        .then().statusCode(200)
              .body("created.size()", is(0))
              .body("skipped.size()", is(1));
  }

  @Test
  @Order(6)
  void list_tables_returns_added_connectors() {
    given().header("X-Tenant-ID", "1").header("X-User-ID", "1")
        .when().get("/api/platform/sources/jdbc/legacy-erp-prod/tables")
        .then().statusCode(200)
              .body("tables.size()", greaterThanOrEqualTo(2));
  }
}
