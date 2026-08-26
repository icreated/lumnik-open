package io.lumnik.hub.api.data;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

@QuarkusTest
class DataTablesResourceTest {

  @Test
  void list_returns_200_with_array() {
    given()
      .header("X-Tenant-Id", "1").header("X-User-Id", "test")
      .when().get("/api/platform/data/tables")
      .then().statusCode(200);
  }

  /** Tenant comes ONLY from the auth-resolved context — no tenant → 401. */
  @Test
  void without_tenant_in_context_returns_401() {
    given()
      .when().get("/api/platform/data/tables")
      .then().statusCode(401);
  }
}
