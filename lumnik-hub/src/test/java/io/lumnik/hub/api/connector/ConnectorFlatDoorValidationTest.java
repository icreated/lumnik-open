package io.lumnik.hub.api.connector;

import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * The flat CRUD door ({@code POST/PUT /api/platform/connectors}) is the one {@code lm connector
 * apply} and the TUI's {@code get → edit → apply} loop drive. It persists {@code config} with no
 * validation — the manifest door ({@code /apply}) runs {@link
 * io.lumnik.hub.validator.ConnectorValidationService} first, this one runs nothing.
 *
 * <p>The concrete, evidenced harm is the one {@code MapColumnsHook}'s javadoc names: a
 * {@code map-columns} rename INTO a hub system column is accepted here, scheduled, and kills the
 * run on its first row — the hook is the only thing between a bad rename and a lost column. The
 * same {@link io.lumnik.hub.connector.mapping.MapColumnsRules} the manifest door and the hook
 * share must refuse it at the flat door too, once, at write time.
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
