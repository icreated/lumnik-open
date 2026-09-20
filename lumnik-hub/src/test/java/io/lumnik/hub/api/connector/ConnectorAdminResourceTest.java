package io.lumnik.hub.api.connector;

import io.lumnik.testkit.auth.TestIdentity;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class ConnectorAdminResourceTest {

  @Inject ConnectorRepository repo;
  @Inject UserTransaction utx;

  @Test
  void list_returns_connectors_for_tenant() throws Exception {
    TenantContext.runAsSystem(() -> {
      try {
        utx.begin();
        var c = new Connector();
        c.setTenantId(1L);
        c.setName("api-test-" + System.nanoTime());
        c.setTypeId("hello-world");
        c.setConfig(Map.of("count", 10));
        repo.persist(c);
        utx.commit();
      } catch (Exception e) { throw new RuntimeException(e); }
    });

    given()
      .headers(TestIdentity.tenantAndUser(1))
      .when().get("/api/platform/connectors")
      .then().statusCode(200)
        .body("$.size()", greaterThanOrEqualTo(1))
        .body("[0].id", notNullValue())
        .body("[0].name", notNullValue());
  }

  @Test
  void create_then_update_then_delete() {
    var created = given()
      .headers(TestIdentity.tenantAndUser(1))
      .contentType("application/json")
      .body("""
        {"tenantId":1,"name":"crud-test","typeId":"hello-world","config":{"count":5},"tags":["billing"]}
        """)
      .when().post("/api/platform/connectors")
      .then().statusCode(201)
        .body("id", notNullValue())
        .body("tags", Matchers.contains("billing"))
        .extract().jsonPath().getString("id");

    given()
      .headers(TestIdentity.tenantAndUser(1))
      .contentType("application/json")
      .body("""
        {"tenantId":1,"name":"crud-test","typeId":"hello-world","config":{"count":99},"tags":["billing","payroll"]}
        """)
      .when().put("/api/platform/connectors/" + created)
      .then().statusCode(200)
        .body("config.count", Matchers.equalTo(99))
        .body("tags", Matchers.containsInAnyOrder("billing", "payroll"));

    given()
      .headers(TestIdentity.tenantAndUser(1))
      .contentType("application/json")
      .body("""
        {"tenantId":1,"name":"crud-test","typeId":"hello-world","config":{"count":99},"tags":["Not Valid"]}
        """)
      .when().put("/api/platform/connectors/" + created)
      .then().statusCode(400);

    given()
      .headers(TestIdentity.tenantAndUser(1))
      .when().delete("/api/platform/connectors/" + created)
      .then().statusCode(204);
  }

  @Test
  void update_without_tags_field_preserves_existing_scope_but_explicit_empty_array_clears_it() {
    var created = given()
      .headers(TestIdentity.tenantAndUser(1))
      .contentType("application/json")
      .body("""
        {"tenantId":1,"name":"tags-preserve-test","typeId":"hello-world","config":{"count":5},"tags":["billing"]}
        """)
      .when().post("/api/platform/connectors")
      .then().statusCode(201)
        .body("tags", Matchers.contains("billing"))
        .extract().jsonPath().getString("id");

    // A PUT body with no "tags" key at all must NOT silently drop scope:billing to unscoped —
    // that would broaden a scoped connector's data to the whole tenant on any caller that isn't
    // replaying a full `get` (#524 follow-up).
    given()
      .headers(TestIdentity.tenantAndUser(1))
      .contentType("application/json")
      .body("""
        {"tenantId":1,"name":"tags-preserve-test","typeId":"hello-world","config":{"count":6}}
        """)
      .when().put("/api/platform/connectors/" + created)
      .then().statusCode(200)
        .body("config.count", Matchers.equalTo(6))
        .body("tags", Matchers.contains("billing"));

    // An explicit empty array is a deliberate clear, not an omission.
    given()
      .headers(TestIdentity.tenantAndUser(1))
      .contentType("application/json")
      .body("""
        {"tenantId":1,"name":"tags-preserve-test","typeId":"hello-world","config":{"count":6},"tags":[]}
        """)
      .when().put("/api/platform/connectors/" + created)
      .then().statusCode(200)
        .body("tags", Matchers.empty());

    given()
      .headers(TestIdentity.tenantAndUser(1))
      .when().delete("/api/platform/connectors/" + created)
      .then().statusCode(204);
  }

  @Test
  void create_and_update_refuse_bad_schedule() {
    given()
      .headers(TestIdentity.tenantAndUser(1))
      .contentType("application/json")
      .body("""
        {"tenantId":1,"name":"schedule-test","typeId":"hello-world","config":{"count":5},"schedule":"not a cron"}
        """)
      .when().post("/api/platform/connectors")
      .then().statusCode(400);

    var created = given()
      .headers(TestIdentity.tenantAndUser(1))
      .contentType("application/json")
      .body("""
        {"tenantId":1,"name":"schedule-test","typeId":"hello-world","config":{"count":5},"schedule":"0 6 * * *"}
        """)
      .when().post("/api/platform/connectors")
      .then().statusCode(201)
        .body("schedule", Matchers.equalTo("0 6 * * *"))
        .extract().jsonPath().getString("id");

    given()
      .headers(TestIdentity.tenantAndUser(1))
      .contentType("application/json")
      .body("""
        {"tenantId":1,"name":"schedule-test","typeId":"hello-world","config":{"count":5},"schedule":"garbage"}
        """)
      .when().put("/api/platform/connectors/" + created)
      .then().statusCode(400);

    given()
      .headers(TestIdentity.tenantAndUser(1))
      .when().delete("/api/platform/connectors/" + created)
      .then().statusCode(204);
  }

  @Test
  void run_triggers_engine_and_returns_run_id() {
    var id = given()
      .headers(TestIdentity.tenantAndUser(1))
      .contentType("application/json")
      .body("""
        {"tenantId":1,"name":"action-test","typeId":"hello-world","config":{"count":10}}
        """)
      .when().post("/api/platform/connectors")
      .then().statusCode(201).extract().jsonPath().getString("id");

    given()
      .headers(TestIdentity.tenantAndUser(1))
      .when().post("/api/platform/connectors/" + id + "/run")
      .then().statusCode(202)
        .body("runId", notNullValue());
  }
}
