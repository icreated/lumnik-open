package io.lumnik.hub.api.connector;

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
      .header("X-Tenant-Id", "1")
      .header("X-User-Id", "test")
      .when().get("/api/platform/connectors")
      .then().statusCode(200)
        .body("$.size()", greaterThanOrEqualTo(1))
        .body("[0].id", notNullValue())
        .body("[0].name", notNullValue());
  }

  @Test
  void create_then_update_then_delete() {
    var created = given()
      .header("X-Tenant-Id", "1").header("X-User-Id", "test")
      .contentType("application/json")
      .body("""
        {"tenantId":1,"name":"crud-test","typeId":"hello-world","config":{"count":5}}
        """)
      .when().post("/api/platform/connectors")
      .then().statusCode(201)
        .body("id", notNullValue())
        .extract().jsonPath().getString("id");

    given()
      .header("X-Tenant-Id", "1").header("X-User-Id", "test")
      .contentType("application/json")
      .body("""
        {"tenantId":1,"name":"crud-test","typeId":"hello-world","config":{"count":99}}
        """)
      .when().put("/api/platform/connectors/" + created)
      .then().statusCode(200)
        .body("config.count", Matchers.equalTo(99));

    given()
      .header("X-Tenant-Id", "1").header("X-User-Id", "test")
      .when().delete("/api/platform/connectors/" + created)
      .then().statusCode(204);
  }

  @Test
  void run_triggers_engine_and_returns_run_id() {
    var id = given()
      .header("X-Tenant-Id", "1").header("X-User-Id", "test")
      .contentType("application/json")
      .body("""
        {"tenantId":1,"name":"action-test","typeId":"hello-world","config":{"count":10}}
        """)
      .when().post("/api/platform/connectors")
      .then().statusCode(201).extract().jsonPath().getString("id");

    given()
      .header("X-Tenant-Id", "1").header("X-User-Id", "test")
      .when().post("/api/platform/connectors/" + id + "/run")
      .then().statusCode(202)
        .body("runId", notNullValue());
  }
}
