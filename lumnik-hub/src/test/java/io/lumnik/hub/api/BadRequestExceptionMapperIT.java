package io.lumnik.hub.api;

import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * {@link BadRequestExceptionMapper} on the wire: the witness that the deployment DISCOVERS it, which
 * no unit test can give. The throw-shaped twin of {@link NotFoundExceptionMapperIT}.
 */
@QuarkusTest
class BadRequestExceptionMapperIT {

  /**
   * The gap this closes: {@code HookOverrideResource.list} refuses with
   * {@code throw new BadRequestException("tenantId query param required")}, and before the mapper the
   * sentence never left the JVM — the response carried status 400 and no content type at all.
   */
  @Test
  void a_deliberate_throw_names_what_the_caller_got_wrong() {
    given().header(TestIdentity.TENANT_HEADER, "999960")
    .when().get("/api/platform/hooks/overrides")
    .then().statusCode(400)
        .contentType(ContentType.JSON)
        .body("error", containsString("tenantId"));
  }

  /**
   * The trap: a refusal thrown WITH a response of its own must cross the mapper untouched. Rebuilding
   * from {@code getMessage()} would answer "HTTP 400 Bad Request" here — see the unit test, which
   * pins that synthesised message directly.
   */
  @Test
  void a_400_that_already_carries_its_body_keeps_it() {
    given().headers(TestIdentity.tenantAndUser(1))
        .queryParam("endpoint", "warehouses")
    .when().get("/api/platform/runs")
    .then().statusCode(400)
        .body("error", equalTo("invalid UUID: warehouses"));
  }

  /**
   * A boundary {@link BadRequestExceptionMapper}'s javadoc claims: a wrong-typed field is answered by
   * Quarkus and never reaches our mapper — so this body has no {@code error} key. Pinned here because a
   * scope claim left in prose alone rots unseen.
   */
  @Test
  void a_wrong_typed_json_field_is_answered_by_quarkus_not_by_this_mapper() {
    given().header(TestIdentity.TENANT_HEADER, "999960")
        .contentType(ContentType.JSON)
        .body("{\"hookId\": \"x\", \"tenantId\": \"abc\"}")
    .when().post("/api/platform/hooks/overrides")
    .then().statusCode(400)
        .body("error", nullValue())
        .body("attributeName", equalTo("tenantId"));
  }

  /**
   * The residue {@link BadRequestExceptionMapper}'s javadoc names, made executable: a body Jackson
   * cannot parse at all is still refused with an empty entity, reaching {@code lm} as "no details from
   * the server" the way the eleven doors did.
   *
   * <p>A tripwire in both directions — the day a Quarkus upgrade dresses this, the red is the signal to
   * drop the residue from the prose, not to restore the silence.
   */
  @Test
  void a_malformed_body_is_still_answered_bodylessly_a_named_residue() {
    given().header(TestIdentity.TENANT_HEADER, "999960")
        .contentType(ContentType.JSON)
        .body("{\"hookId\": \"x\", \"tenantId\": ")
    .when().post("/api/platform/hooks/overrides")
    .then().statusCode(400)
        .body(emptyOrNullString());
  }
}
