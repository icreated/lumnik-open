package io.lumnik.hub.view;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * The one behavioural test behind SavedViews' 404 — the door {@code lm} and the PWA hit to fetch a
 * view by name. It was converted to {@link io.lumnik.hub.api.ApiErrors#notFound} in #393 but had NO
 * test proving the body actually reaches the client; {@link io.lumnik.hub.api.NotFoundBodyInventoryTest}
 * pins the source shape, this pins the runtime contract.
 *
 * <p>The {@code Accept} header is {@code lm}'s real one — {@code application/yaml,
 * application/json;q=0.9} ({@code lm/internal/client/view.go}, the GET-by-name call). This resource declares JSON
 * AND YAML at the class level, so a yaml-only {@code Accept} could 406 the JSON error body; the
 * JSON fallback in the client's header is what avoids that, and this asserts the 404 comes back as
 * JSON, not a 406.
 */
@QuarkusTest
class SavedViewsResourceIT {

  @Test
  void get_unknown_view_is_404_with_a_json_body_naming_it() {
    given().header("X-Tenant-Id", "1")
        .header("Accept", "application/yaml, application/json;q=0.9")
    .when().get("/api/views/no-such-view")
    .then().statusCode(404)
        .contentType(ContentType.JSON)
        .body("error", containsString("no-such-view"));
  }
}
