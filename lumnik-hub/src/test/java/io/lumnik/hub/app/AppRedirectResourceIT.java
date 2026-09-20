package io.lumnik.hub.app;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * `GET /app` fell through to the API's not-found mapper because the PWA is served as static
 * resources under `/app/` only — no route answers the bare, slash-less path users actually type
 * or share. #572.
 */
@QuarkusTest
class AppRedirectResourceIT {

  @Test
  void bare_path_redirects_permanently_to_the_trailing_slash() {
    given().redirects().follow(false)
    .when().get("/app")
    .then().statusCode(301)
        .header("Location", equalTo("/app/"));
  }

  @Test
  void query_string_survives_the_redirect() {
    given().redirects().follow(false)
    .when().get("/app?m=stock")
    .then().statusCode(301)
        .header("Location", equalTo("/app/?m=stock"));
  }

  /**
   * A caller-supplied Host header must never come back in the Location: reflecting it turns the
   * redirect into an open redirect to whatever the attacker put there. The fix is a relative
   * Location — same-origin by construction, nothing to spoof.
   */
  @Test
  void a_spoofed_host_header_is_not_reflected_in_the_redirect() {
    given().redirects().follow(false)
        .header("Host", "evil.example.com")
    .when().get("/app")
    .then().statusCode(301)
        .header("Location", equalTo("/app/"))
        .header("Location", not(endsWith("evil.example.com/app/")));
  }
}
