package io.lumnik.hub.api.migration;

import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class MigrationAdminResourceTest {
  @Test
  void list_migrations_returns_per_schema_status() {
    given().headers(TestIdentity.tenantAndUser(1))
      .when().get("/api/platform/migrations")
      .then().statusCode(200)
        .body(notNullValue());
  }
}
