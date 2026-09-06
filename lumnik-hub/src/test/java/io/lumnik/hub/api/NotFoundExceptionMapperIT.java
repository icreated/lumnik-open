package io.lumnik.hub.api;

import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * {@link NotFoundExceptionMapper} dresses a thrown 404 in the frozen {@code {"error": …}} body — the
 * throw-shaped twin of the bare-404 return sites #418 fixed. Pins both paths: a deliberate throw names
 * what was missing, and a framework 404 (unmatched route) is JSON, not RESTEasy's bodyless default.
 */
@QuarkusTest
class NotFoundExceptionMapperIT {

  @Test
  void a_deliberate_throw_names_what_was_missing() {
    given().header(TestIdentity.TENANT_HEADER, "1")
    .when().post("/api/platform/deliveries/999999/replay")
    .then().statusCode(404)
        .contentType(ContentType.JSON)
        .body("error", containsString("delivery"))
        .body("error", containsString("999999"));
  }

  @Test
  void a_framework_404_on_an_unmatched_route_is_json_not_bodyless() {
    given().header(TestIdentity.TENANT_HEADER, "1")
    .when().get("/api/zzz-no-such-route")
    .then().statusCode(404)
        .contentType(ContentType.JSON)
        .body("error", notNullValue())
        // Tripwire: the framework body must stay a generic phrase, never echo the request path — a
        // regression alarm if a future RESTEasy emits the URL in NotFoundException.getMessage().
        .body("error", not(containsString("zzz-no-such-route")));
  }
}
