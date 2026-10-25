package io.lumnik.hub.api.connector;

import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * The flat CRUD door ({@code POST/PUT /api/platform/connectors}) is the one {@code lm connector
 * apply} and the TUI's {@code get → edit → apply} loop drive. It runs no
 * {@link io.lumnik.hub.validator.ConnectorValidationService} at all — the manifest door
 * ({@code /apply}) does. What this class holds is the short list of things it refuses ANYWAY,
 * each because leaving it through costs something concrete and silent.
 *
 * <p><b>A map-columns rename into a system column.</b> The harm {@code MapColumnsHook}'s javadoc
 * names: accepted here, scheduled, and it kills the run on its first row — the hook is the only
 * thing between a bad rename and a lost column. The same
 * {@link io.lumnik.hub.connector.mapping.MapColumnsRules} the manifest door and the hook share
 * refuses it here too, once, at write time.
 *
 * <p><b>A malformed run window.</b> {@code RunWindow.contains} is fail-open, so a typo means no
 * fence rather than a refusal — and this door only started carrying {@code window} with #499, so
 * the field arrived with a way to lose itself. {@code schedule} has the identical hole and is
 * deliberately NOT closed here: it predates this change and wants the cron validator's own
 * wording.
 */
@QuarkusTest
class ConnectorFlatDoorValidationTest {

  /** A csv connector whose config renames a source column INTO {@code tenant_id}, a system column. */
  private static final String BAD_MAP_COLUMNS = """
      {"tenantId":1,"name":"flat-badmap-%d","typeId":"csv-file","config":{
        "kind":"csv-file",
        "mapping":{"row":[{"map-columns":{"rename":{"CUST_NM":"tenant_id"}}}]}
      }}
      """;

  /**
   * A malformed fence is refused here, not stored.
   *
   * <p>{@code RunWindow.contains} is fail-open — an unparseable spec answers "yes, run" — so a
   * typo accepted at this door means NO fence, silently. The flat door validates almost nothing
   * by design, but the window is a field this door started carrying with #499, and a field a door
   * emits and silently mangles is the loss that ticket was about.
   */
  @Test
  void create_refuses_a_malformed_run_window() {
    given()
      .headers(TestIdentity.tenantAndUser(1))
      .contentType("application/json")
      .body(("{\"tenantId\":1,\"name\":\"flat-badwindow-" + System.nanoTime()
             + "\",\"typeId\":\"csv-file\",\"window\":\"22h-6h\",\"config\":{\"kind\":\"csv-file\"}}"))
      .when().post("/api/platform/connectors")
      .then().statusCode(400);
  }

  @Test
  void create_accepts_a_well_formed_run_window() {
    given()
      .headers(TestIdentity.tenantAndUser(1))
      .contentType("application/json")
      .body(("{\"tenantId\":1,\"name\":\"flat-goodwindow-" + System.nanoTime()
             + "\",\"typeId\":\"csv-file\",\"window\":\"22:00-06:00\",\"config\":{\"kind\":\"csv-file\"}}"))
      .when().post("/api/platform/connectors")
      .then().statusCode(201)
      .body("window", equalTo("22:00-06:00"));
  }

  @Test
  void create_refuses_map_columns_rename_into_a_system_column() {
    given()
      .headers(TestIdentity.tenantAndUser(1))
      .contentType("application/json")
      .body(String.format(BAD_MAP_COLUMNS, System.nanoTime()))
      .when().post("/api/platform/connectors")
      .then().statusCode(400)
        .body(containsString("tenant_id"));
  }

  /** #1475: a tier written as a map was stored, and refused only by the run (#1464). */
  @Test
  void create_refuses_a_mapping_tier_written_as_a_map() {
    given()
      .headers(TestIdentity.tenantAndUser(1))
      .contentType("application/json")
      .body(String.format("""
        {"tenantId":1,"name":"flat-maptier-%d","typeId":"csv-file","config":{
          "kind":"csv-file",
          "mapping":{"row":{"map-columns":{"rename":{"CUST_NM":"name"}}}}
        }}
        """, System.nanoTime()))
      .when().post("/api/platform/connectors")
      .then().statusCode(400)
        .body("error", equalTo("config 'mapping.row' row must be a list of hooks, "
            + "e.g. - upper: { columns: [name] } (got object)"));
  }

  @Test
  void update_refuses_map_columns_rename_into_a_system_column() {
    // A clean connector goes in through the same flat door, then a bad edit is applied to it.
    String id = given()
      .headers(TestIdentity.tenantAndUser(1))
      .contentType("application/json")
      .body(String.format("""
        {"tenantId":1,"name":"flat-goodmap-%d","typeId":"csv-file","config":{"kind":"csv-file"}}
        """, System.nanoTime()))
      .when().post("/api/platform/connectors")
      .then().statusCode(201)
      .extract().jsonPath().getString("id");

    given()
      .headers(TestIdentity.tenantAndUser(1))
      .contentType("application/json")
      .body(String.format(BAD_MAP_COLUMNS, System.nanoTime()))
      .when().put("/api/platform/connectors/" + id)
      .then().statusCode(400)
        .body(containsString("tenant_id"));
  }
}
