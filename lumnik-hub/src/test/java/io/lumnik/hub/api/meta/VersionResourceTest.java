package io.lumnik.hub.api.meta;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class VersionResourceTest {

  @Test
  void version_returns_server_version() {
    given()
      .header("X-Tenant-Id", "1")
      .header("X-User-Id", "test")
      .when().get("/api/platform/version")
      .then().statusCode(200)
        .body("version", notNullValue())
        .body("apiVersion", notNullValue());
  }
}
