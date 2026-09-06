package io.lumnik.hub.api.data;

import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

@QuarkusTest
class DataRowResourceTest {

  @Test
  void unknown_hash_returns_404() {
    given()
      .headers(TestIdentity.tenantAndUser(1))
      .when().get("/api/platform/data/row/nonexistent-hash-" + System.nanoTime())
      .then().statusCode(404);
  }

  /** Tenant comes ONLY from the auth-resolved context — no tenant → 401. */
  @Test
  void without_tenant_in_context_returns_401() {
    given()
      .when().get("/api/platform/data/row/whatever-hash")
      .then().statusCode(401);
  }
}
