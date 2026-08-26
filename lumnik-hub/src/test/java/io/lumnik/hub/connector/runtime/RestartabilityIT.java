package io.lumnik.hub.connector.runtime;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.*;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorCursorEntity;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.repository.ConnectorCursorEntityRepository;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@QuarkusTest
class RestartabilityIT {

  @Inject ConnectorEngine engine;
  @Inject ConnectorRepository connectors;
  @Inject ConnectorEndpointRepository endpoints;
  @Inject ConnectorCursorEntityRepository cursors;
  @Inject UserTransaction utx;

  @Test
  void run_resumes_from_persisted_cursor() throws Exception {
    UUID id = TenantContext.callAsSystem(() -> {
      try {
        utx.begin();
        var c = new Connector();
        c.setTenantId(1L);
        c.setName("restart-" + System.nanoTime());
        c.setTypeId("hello-world");
        c.setConfig(Map.of("count", 1000));
        connectors.persist(c);
        connectors.flush();

        // Explicit endpoint (cursors are endpoint-keyed): the run resolves THIS endpoint.
        var ep = new ConnectorEndpoint();
        ep.setTenantId(1L);
        ep.setConnectorId(c.getId());
        ep.setName("hello");
        ep.setMethod("GET");
        ep.setPath("/hello");
        ep.setEnabled(true);
        ep.setConfig(Map.of("count", 1000));
        endpoints.persist(ep);
        endpoints.flush();

        // Pre-seed the endpoint's cursor at offset=600 to simulate a previous run that died
        // at chunk 2 of 2.
        var cursor = new ConnectorCursorEntity();
        cursor.setTenantId(1L);
        cursor.setEndpointId(ep.getId());
        cursor.setConnectorId(c.getId());
        cursor.setCursorValue(Cursors.offset(600, 500).value());
        cursors.persist(cursor);
        utx.commit();
        return c.getId();
      } catch (Exception e) { throw new RuntimeException(e); }
    });

    var status = engine.runSync(id, new RunTrigger.Manual(1L));
    assertThat(status).isInstanceOf(RunStatus.Completed.class);
    // 1000 - 600 = 400 records remained; verify run history shows 400 records read
    var completed = (RunStatus.Completed) status;
    assertThat(completed.records()).isEqualTo(400);
  }
}
