package io.lumnik.hub.connector.jdbc;

import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Full RAG-Hub E2E: PostgreSQL source → add → list-tables round-trip.
 *
 * <p>Requires a running Ollama (llm-first template generation). Disabled in CI
 * via {@code -Dollama=off}. Container is managed manually to avoid the
 *
 * @QuarkusTest + @Testcontainers lifecycle ordering issue.
 */
@QuarkusTest
@DisabledIfSystemProperty(named = "ollama", matches = "off")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JdbcRagHubE2EIT {

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
  void add_three_tables_as_jdbc_source() {
    given().headers(TestIdentity.tenantAndUser(1))
        .contentType(ContentType.JSON)
        .body(Map.of(
            "sourceName", "legacy-erp-e2e",
            "url", jdbcUrl,
            "user", "u",
            "password", "p",
            "tables", List.of(
                Map.of("table", "c_bpartner", "watermark", "updated", "mode", "watermark"),
                Map.of("table", "m_product",  "watermark", "updated", "mode", "watermark"),
                Map.of("table", "c_order",    "watermark", "updated", "mode", "watermark"))))
        .when().post("/api/platform/sources/jdbc/add")
        .then().statusCode(200)
               .body("created.size()", is(3));
  }

  @Test
  @Order(2)
  void list_tables_returns_three_registered_connectors() {
    given().headers(TestIdentity.tenantAndUser(1))
        .when().get("/api/platform/sources/jdbc/legacy-erp-e2e/tables")
        .then().statusCode(200)
               .body("tables.size()", is(3));
  }
}
