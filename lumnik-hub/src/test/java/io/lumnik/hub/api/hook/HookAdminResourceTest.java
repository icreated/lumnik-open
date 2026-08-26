package io.lumnik.hub.api.hook;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;

@QuarkusTest
class HookAdminResourceTest {
  @Test
  void list_hooks_returns_array() {
    given().header("X-Tenant-Id","1").header("X-User-Id","test")
      .when().get("/api/platform/hooks")
      .then().statusCode(200);
  }
}
