package io.lumnik.hub.hook;

import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;

/** #1402: what {@code lm hook scripts} reads. The test hub sets no hooks directory. */
@QuarkusTest
class HookScriptResourceTest {

  @Test
  void a_hub_without_a_hooks_directory_says_so_rather_than_listing_nothing() {
    given().headers(TestIdentity.tenantAndUser(1))
      .when().get("/api/platform/hooks/scripts")
      .then().statusCode(200)
      .body("directory", equalTo(false))
      .body("scripts", empty());
  }
}
