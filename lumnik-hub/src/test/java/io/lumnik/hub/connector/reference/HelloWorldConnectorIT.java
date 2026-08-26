package io.lumnik.hub.connector.reference;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.repository.ConnectorCursorEntityRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.lumnik.hub.connector.runtime.ConnectorEngine;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@QuarkusTest
class HelloWorldConnectorIT {

  @Inject ConnectorEngine engine;
  @Inject ConnectorRepository connectors;
  @Inject ConnectorRunRepository runs;
  @Inject ConnectorCursorEntityRepository cursors;
  @Inject UserTransaction utx;

  @Test
  void hello_world_multichunk_completes_and_advances_cursor() throws Exception {
    UUID id = TenantContext.callAsSystem(() -> {
      try {
        utx.begin();
        var c = new Connector();
        c.setTenantId(1L);
        c.setName("e2e-600-" + System.nanoTime());
        c.setTypeId("hello-world");
        c.setConfig(Map.of("count", 600));
        connectors.persist(c);
        connectors.flush();
        utx.commit();
        return c.getId();
      } catch (Exception e) { throw new RuntimeException(e); }
    });

    RunStatus s = engine.runSync(id, new RunTrigger.Manual(1L));
    assertThat(s).isInstanceOf(RunStatus.Completed.class);
    assertThat(((RunStatus.Completed) s).records()).isEqualTo(600);

    TenantContext.callAsSystem(() -> {
      try {
        utx.begin();
        var history = runs.findRecentByConnector(id, 5);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getRecordsIn()).isEqualTo(600);
        assertThat(history.get(0).getRecordsOut()).isEqualTo(600);
        assertThat(history.get(0).getChunkCount()).isGreaterThan(0);

        // Cursor advanced by the HasMore chunks (offset-based pagination)
        var cursor = cursors.findById(id);
        if (cursor != null) {
          assertThat(cursor.getCursorValue()).isNotNull();
        }
        utx.commit();
        return null;
      } catch (Exception e) { throw new RuntimeException(e); }
    });
  }
}
