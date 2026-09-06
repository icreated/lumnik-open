package io.lumnik.hub.api.meta;

import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class VersionResourceTest {

  @Test
  void version_returns_server_version() {
    given()
      .headers(TestIdentity.tenantAndUser(1))
      .when().get("/api/platform/version")
      .then().statusCode(200)
        .body("version", notNullValue())
        .body("apiVersion", notNullValue());
  }
}
