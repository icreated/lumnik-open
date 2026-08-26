package io.lumnik.hub.connector.jdbc;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Verifies that two different tenants calling /add with the same source name
 * get isolated connectors (different IDs) and cannot see each other's data
 * via /listTables.
 *
 * <p>Uses Approach B (plain RestAssured + X-Tenant-ID header) — consistent with
 * JdbcSourceResourceIT. Container lifecycle managed manually to avoid the
 * @Testcontainers + @QuarkusTest ordering issue documented in Task 13.
 *
 * <p>Tenant IDs 11 and 12 are used to avoid collisions with other ITs that
 * use tenant 1.
 */
@QuarkusTest
class JdbcMultiTenancyIT {

  static PostgreSQLContainer<?> PG;
  static String jdbcUrl;

  @BeforeAll
  static void startSourceDb() {
    PG = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("mt_source").withUsername("u").withPassword("p");
    PG.start();
    jdbcUrl = PG.getJdbcUrl();
  }

  @AfterAll
  static void stopSourceDb() {
    if (PG != null) PG.stop();
  }

  @Test
  void two_tenants_have_isolated_connectors() {
    String sourceName = "mt-source-" + System.currentTimeMillis();

    // ── Tenant 11 adds a connector ────────────────────────────────────────────
    String connId1 = given()
        .header("X-Tenant-ID", "11").header("X-User-ID", "1")
        .contentType(ContentType.JSON)
        .body(Map.of(
            "sourceName", sourceName,
            "url", jdbcUrl, "user", "u", "password", "p",
            "tables", List.of(Map.of("table", "pg_class", "watermark", "reltuples", "mode", "full")),
            "templateMode", "dump"))
        .when().post("/api/platform/sources/jdbc/add")
        .then().statusCode(200)
               .body("created.size()", is(1))
               .extract().path("created[0].connectorId");

    // ── Tenant 12 adds a connector with the same source name ─────────────────
    String connId2 = given()
        .header("X-Tenant-ID", "12").header("X-User-ID", "1")
        .contentType(ContentType.JSON)
        .body(Map.of(
            "sourceName", sourceName,
            "url", jdbcUrl, "user", "u", "password", "p",
            "tables", List.of(Map.of("table", "pg_class", "watermark", "reltuples", "mode", "full")),
            "templateMode", "dump"))
        .when().post("/api/platform/sources/jdbc/add")
        .then().statusCode(200)
               .body("created.size()", is(1))
               .extract().path("created[0].connectorId");

    // ── Connector IDs must differ — they are in separate tenant namespaces ────
    Assertions.assertNotEquals(connId1, connId2,
        "Two tenants must not share the same connector ID");

    // ── Tenant 11 sees exactly its own connector via listTables ───────────────
    given()
        .header("X-Tenant-ID", "11").header("X-User-ID", "1")
        .when().get("/api/platform/sources/jdbc/" + sourceName + "/tables")
        .then().statusCode(200)
               .body("tables.size()", is(1));

    // ── Tenant 12 sees exactly its own connector via listTables ───────────────
    given()
        .header("X-Tenant-ID", "12").header("X-User-ID", "1")
        .when().get("/api/platform/sources/jdbc/" + sourceName + "/tables")
        .then().statusCode(200)
               .body("tables.size()", is(1));

    // ── Cross-tenant leak check: a third tenant sees nothing ──────────────────
    given()
        .header("X-Tenant-ID", "99").header("X-User-ID", "1")
        .when().get("/api/platform/sources/jdbc/" + sourceName + "/tables")
        .then().statusCode(200)
               .body("tables.size()", is(0));
  }
}
