package io.lumnik.hub.api.hook;

import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;

@QuarkusTest
class HookAdminResourceTest {
  @Test
  void list_hooks_returns_array() {
    given().headers(TestIdentity.tenantAndUser(1))
      .when().get("/api/platform/hooks")
      .then().statusCode(200);
  }
}
