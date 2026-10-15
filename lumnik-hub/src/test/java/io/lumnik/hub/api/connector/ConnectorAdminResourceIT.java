package io.lumnik.hub.api.connector;

import io.lumnik.testkit.auth.TestIdentity;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.connector.domain.ConnectorRunStatus;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class ConnectorAdminResourceIT {

  @Inject ConnectorRepository connectors;
  @Inject ConnectorRunRepository runs;
  @Inject UserTransaction utx;

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
}
