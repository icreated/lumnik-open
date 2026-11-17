package io.lumnik.hub.api.connector;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.testkit.auth.TestIdentity;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.connector.domain.ConnectorRunStatus;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class ConnectorAdminResourceIT {

  @Inject ConnectorRepository connectors;
  @Inject ConnectorRunRepository runs;
  @Inject UserTransaction utx;
  @Inject @DataSource("admin") AgroalDataSource adminDs;

  @Test
  void list_includes_lastRunAt_and_status_from_latest_run() throws Exception {
    var connectorId = TenantContext.callAsSystem(() -> {
      utx.begin();
      Connector cc = new Connector();
      cc.setTenantId(1L);
      cc.setName("with-run-" + System.nanoTime());
      cc.setTypeId("hello-world");
      cc.setConfig(Map.of("count", 1));
      cc.setEnabled(true);
      connectors.persist(cc);

      ConnectorRun r = new ConnectorRun();
      r.setTenantId(1L);
      r.setConnectorId(cc.getId());
      r.setStatus(ConnectorRunStatus.COMPLETED.sqlValue());
      r.setSyncMode("FULL");
      r.setStartedAt(Instant.now().minusSeconds(60));
      r.setEndedAt(Instant.now());
      r.setTriggeredBy("manual");
      runs.persist(r);
      utx.commit();
      return cc.getId().toString();
    });

    given().headers(TestIdentity.tenantAndUser(1))
        .when().get("/api/platform/connectors")
        .then().statusCode(200)
               .body("find { it.id == '" + connectorId + "' }.status", equalTo(ConnectorRunStatus.COMPLETED.sqlValue()))
               .body("find { it.id == '" + connectorId + "' }.lastRunAt", notNullValue());
  }

  /**
   * #2003: a connector's inline credentials never come back through a read door. The config read back that way and
   * sent again keeps them; a new value replaces them.
   */
  @Test
  void inline_credentials_are_served_redacted_and_kept_on_a_round_trip() throws Exception {
    String name = "inline-" + System.nanoTime();
    var id = TenantContext.callAsSystem(() -> {
      utx.begin();
      Connector cc = new Connector();
      cc.setTenantId(1L);
      cc.setName(name);
      cc.setTypeId("rest-generic");
      cc.setConfig(Map.of("base_url", "https://api-user:s3cret@api.example/v1",
          "auth", Map.of("kind", "bearer", "token_value", "tok-123")));
      cc.setEnabled(false);
      connectors.persist(cc);
      utx.commit();
      return cc.getId();
    });
    String door = "/api/platform/connectors/" + id;

    Map<String, Object> read = given().headers(TestIdentity.tenantAndUser(1))
        .when().get(door)
        .then().statusCode(200)
            .body("config.base_url", equalTo("https://api-user:%3Credacted%3E@api.example/v1"))
            .body("config.auth.token_value", equalTo("<redacted>"))
            .body(not(containsString("tok-123")))
            .body(not(containsString("s3cret")))
        .extract().jsonPath().getMap("config");

    given().headers(TestIdentity.tenantAndUser(1)).contentType("application/json")
        .body(Map.of("name", name, "typeId", "rest-generic", "enabled", false, "config", read))
        .when().put(door).then().statusCode(200);
    assertStored(id, "tok-123", "https://api-user:s3cret@api.example/v1");

    Map<String, Object> rotated = new LinkedHashMap<>(read);
    rotated.put("auth", Map.of("kind", "bearer", "token_value", "tok-456"));
    given().headers(TestIdentity.tenantAndUser(1)).contentType("application/json")
        .body(Map.of("name", name, "typeId", "rest-generic", "enabled", false, "config", rotated))
        .when().put(door).then().statusCode(200);
    assertStored(id, "tok-456", "https://api-user:s3cret@api.example/v1");
  }

  /** Read from the table, not from an entity a session may still hold. */
  private void assertStored(UUID id, String token, String baseUrl) {
    assertThat(Jdbc.queryOne(adminDs, "stored", "SELECT config->>'base_url', config->'auth'->>'token_value' "
        + "FROM connector.connector WHERE id = ?", ps -> ps.setObject(1, id),
        rs -> rs.getString(1) + " " + rs.getString(2))).contains(baseUrl + " " + token);
  }
}
