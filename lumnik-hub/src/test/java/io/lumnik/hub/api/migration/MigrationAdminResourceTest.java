package io.lumnik.hub.api.migration;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class MigrationAdminResourceTest {
  @Test
  void list_migrations_returns_per_schema_status() {
    given().header("X-Tenant-Id","1").header("X-User-Id","test")
      .when().get("/api/platform/migrations")
      .then().statusCode(200)
        .body(notNullValue());
  }
}
