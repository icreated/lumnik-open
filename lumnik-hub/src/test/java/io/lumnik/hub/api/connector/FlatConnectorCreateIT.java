package io.lumnik.hub.api.connector;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.runtime.ConnectorEngine;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static io.lumnik.hub.testsupport.EventItSupport.scalar;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * The flat door ({@code lm connector apply -f}) creates connectors that run (#1189), in the
 * caller's tenant (#1190). A create is followed by a real run for every type the door creates;
 * a type whose endpoints the flat shape cannot carry is refused, pointing at {@code lm apply -f}.
 */
@QuarkusTest
class FlatConnectorCreateIT {

  @Inject ConnectorEngine engine;
  @Inject ConnectorEndpointRepository endpoints;

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  private static RequestSpecification asTenant1() {
    return given().headers(TestIdentity.tenantAndUser(1)).contentType("application/json");
  }

  private static String create(String body) {
    return asTenant1().body(body).when().post("/api/platform/connectors")
        .then().statusCode(201).extract().jsonPath().getString("id");
  }

  private RunStatus run(String id) {
    return TenantContext.callAsSystem(() -> engine.runSync(UUID.fromString(id), new RunTrigger.Manual(1L)));
  }

  private static void delete(String id) {
    asTenant1().when().delete("/api/platform/connectors/" + id).then().statusCode(204);
  }

  @Test
  void a_csv_file_connector_created_flat_runs_into_the_table_named_after_it() throws Exception {
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    Path csv = Files.createTempFile("flat_csv_", ".csv");
    csv.toFile().deleteOnExit();
    Files.writeString(csv, "code,cname\nC-1,Alice\nC-2,Bob\n");

    String id = create("""
        {"name":"flat-csv-%s","typeId":"csv-file","enabled":true,"config":{"kind":"csv-file",
         "after_process":"none","parser":{"has_header":true},
         "transport":{"kind":"local","path":"%s"}}}
        """.formatted(suffix, csv.toAbsolutePath()));
    try {
      RunStatus status = run(id);
      assertThat(status).as("flat csv run: %s", status).isInstanceOf(RunStatus.Completed.class);
      assertThat(scalar(adminDs, "SELECT count(*) FROM connector.\"t_flat_csv_" + suffix + "\""))
          .isEqualTo(2L);
    } finally {
      delete(id);
    }
  }

  /**
   * The flat door and the manifest door write the SAME default endpoint for a csv-file connector:
   * one rule, not two that drift.
   */
  @Test
  void the_flat_door_writes_the_endpoint_the_manifest_door_writes() {
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    String flatId = create("""
        {"name":"same-ep-%s","typeId":"csv-file","enabled":true,"config":{"kind":"csv-file",
         "transport":{"kind":"local","path":"/tmp/same-ep.csv"}}}
        """.formatted(suffix));
    ConnectorEndpoint flat = only(flatId);
    delete(flatId);

    String manifestId = given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
        .contentType("application/yaml").body("""
        apiVersion: connectors.lumnik.io/v1
        kind: Connector
        metadata:
          name: same-ep-%s
          connector_type: csv-file
        spec:
          transport: { kind: local, path: /tmp/same-ep.csv }
          parser: { has_header: true }
          after_process: none
          target: {}
        """.formatted(suffix))
        .when().post("/api/platform/connectors/apply")
        .then().statusCode(200).extract().jsonPath().getString("connectorId");
    try {
      ConnectorEndpoint manifest = only(manifestId);
      assertThat(flat.getName()).isEqualTo(manifest.getName());
      assertThat(flat.getPath()).isEqualTo(manifest.getPath());
      assertThat(flat.getConfig()).isEqualTo(manifest.getConfig());
    } finally {
      delete(manifestId);
    }
  }

  private ConnectorEndpoint only(String connectorId) {
    List<ConnectorEndpoint> eps = TenantContext.callAsSystem(
        () -> endpoints.list("connectorId = ?1", UUID.fromString(connectorId)));
    assertThat(eps).hasSize(1);
    return eps.get(0);
  }

  @Test
  void a_hello_world_connector_created_flat_runs() {
    String id = create("""
        {"name":"flat-hello-%d","typeId":"hello-world","enabled":true,"config":{"count":3}}
        """.formatted(System.nanoTime()));
    try {
      RunStatus status = run(id);
      assertThat(status).as("flat hello-world run: %s", status).isInstanceOf(RunStatus.Completed.class);
    } finally {
      delete(id);
    }
  }

  /** Their endpoints (tables, REST paths, a topic) are not in the flat shape: no run could succeed. */
  @ParameterizedTest
  @ValueSource(strings = {"jdbc-generic", "rest-generic", "kafka", "no-such-type"})
  void a_type_whose_endpoints_the_flat_shape_cannot_carry_is_refused_with_the_gesture(String type) {
    asTenant1().body("""
        {"name":"flat-refused-%d","typeId":"%s","enabled":true,"config":{"kind":"%s"}}
        """.formatted(System.nanoTime(), type, type))
        .when().post("/api/platform/connectors")
        .then().statusCode(400)
        .body("error", containsString(type))
        .body("error", containsString("lm apply -f"));
  }

  /** An update cannot walk around the create refusal, nor turn a connector into a type it has no endpoint for. */
  @ParameterizedTest
  @ValueSource(strings = {"csv-file", "jdbc-generic"})
  void an_update_cannot_change_the_type(String type) {
    String name = "upd-type-" + System.nanoTime();
    String id = create("""
        {"name":"%s","typeId":"hello-world","config":{"count":1}}
        """.formatted(name));
    try {
      asTenant1().body("""
          {"name":"%s","typeId":"%s","config":{"kind":"%s"}}
          """.formatted(name, type, type))
          .when().put("/api/platform/connectors/" + id)
          .then().statusCode(400)
          .body("error", containsString("typeId"));
      asTenant1().when().get("/api/platform/connectors/" + id)
          .then().statusCode(200).body("typeId", equalTo("hello-world"));
    } finally {
      delete(id);
    }
  }

  @Test
  void an_update_without_typeId_keeps_the_stored_type() {
    String name = "upd-notype-" + System.nanoTime();
    String id = create("""
        {"name":"%s","typeId":"hello-world","config":{"count":1}}
        """.formatted(name));
    try {
      asTenant1().body("""
          {"name":"%s","config":{"count":2}}
          """.formatted(name))
          .when().put("/api/platform/connectors/" + id)
          .then().statusCode(200).body("typeId", equalTo("hello-world"));
    } finally {
      delete(id);
    }
  }

  // ── #1190: the tenant is the caller's ───────────────────────────────────────

  @Test
  void a_create_without_tenantId_lands_in_the_callers_tenant() {
    String id = asTenant1().body("""
        {"name":"no-tenant-%d","typeId":"hello-world","config":{"count":1}}
        """.formatted(System.nanoTime()))
        .when().post("/api/platform/connectors")
        .then().statusCode(201).body("tenantId", equalTo(1))
        .extract().jsonPath().getString("id");
    delete(id);
  }

  @Test
  void a_create_naming_another_tenant_is_a_400_not_a_500() {
    asTenant1().body("""
        {"tenantId":2,"name":"other-tenant-%d","typeId":"hello-world","config":{"count":1}}
        """.formatted(System.nanoTime()))
        .when().post("/api/platform/connectors")
        .then().statusCode(400)
        .body("error", containsString("tenantId"));
  }

  @Test
  void an_update_naming_another_tenant_is_a_400_and_without_one_succeeds() {
    String name = "upd-tenant-" + System.nanoTime();
    String id = create("""
        {"name":"%s","typeId":"hello-world","config":{"count":1}}
        """.formatted(name));
    try {
      asTenant1().body("""
          {"tenantId":2,"name":"%s","typeId":"hello-world","config":{"count":2}}
          """.formatted(name))
          .when().put("/api/platform/connectors/" + id)
          .then().statusCode(400)
          .body("error", containsString("tenantId"));
      asTenant1().body("""
          {"name":"%s","typeId":"hello-world","config":{"count":2}}
          """.formatted(name))
          .when().put("/api/platform/connectors/" + id)
          .then().statusCode(200).body("tenantId", equalTo(1));
    } finally {
      delete(id);
    }
  }

  // ── #1224: a name already taken is a 409 naming its holder, never the constraint's 500 ──────────

  @Test
  void a_create_under_a_taken_name_is_a_409_naming_the_holder_and_the_gesture() {
    String name = "taken-" + System.nanoTime();
    String holder = create("""
        {"name":"%s","typeId":"hello-world","config":{"count":1}}
        """.formatted(name));
    try {
      asTenant1().body("""
          {"name":"%s","typeId":"hello-world","config":{"count":2}}
          """.formatted(name))
          .when().post("/api/platform/connectors")
          .then().statusCode(409)
          .body("error", containsString(name))
          .body("error", containsString(holder))
          .body("id", equalTo(holder));
    } finally {
      delete(holder);
    }
  }

  @Test
  void a_rename_onto_a_taken_name_is_a_409_and_changes_nothing() {
    String taken = "rename-taken-" + System.nanoTime(), mine = "rename-mine-" + System.nanoTime();
    String holder = create("""
        {"name":"%s","typeId":"hello-world","config":{"count":1}}
        """.formatted(taken));
    String id = create("""
        {"name":"%s","typeId":"hello-world","config":{"count":1}}
        """.formatted(mine));
    try {
      asTenant1().body("""
          {"name":"%s","config":{"count":2}}
          """.formatted(taken))
          .when().put("/api/platform/connectors/" + id)
          .then().statusCode(409)
          .body("error", containsString(taken))
          .body("id", equalTo(holder));
      asTenant1().when().get("/api/platform/connectors/" + id)
          .then().statusCode(200).body("name", equalTo(mine));
      // keeping one's own name is not a conflict
      asTenant1().body("""
          {"name":"%s","config":{"count":2}}
          """.formatted(mine))
          .when().put("/api/platform/connectors/" + id)
          .then().statusCode(200);
    } finally {
      delete(id);
      delete(holder);
    }
  }
}
