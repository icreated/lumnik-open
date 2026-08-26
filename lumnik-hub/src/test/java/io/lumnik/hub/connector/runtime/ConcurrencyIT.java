package io.lumnik.hub.connector.runtime;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.*;

@QuarkusTest
class ConcurrencyIT {

  @Inject ConnectorEngine engine;
  @Inject ConnectorRepository connectors;
  @Inject UserTransaction utx;

  @Test
  void five_concurrent_runs_complete_independently() throws Exception {
    List<UUID> ids = new ArrayList<>();
    TenantContext.runAsSystem(() -> {
      try {
        utx.begin();
        for (int i = 0; i < 5; i++) {
          var c = new Connector();
          c.setTenantId(1L);
          c.setName("conc-" + i + "-" + System.nanoTime());
          c.setTypeId("hello-world");
          c.setConfig(Map.of("count", 50));
          connectors.persist(c);
          ids.add(c.getId());
        }
        connectors.flush();
        utx.commit();
      } catch (Exception e) { throw new RuntimeException(e); }
    });

    List<Future<RunStatus>> futures = new ArrayList<>();
    for (UUID id : ids) futures.add(engine.submitAsync(id, new RunTrigger.Manual(1L)));

    int completed = 0;
    for (var f : futures) {
      var s = f.get();
      if (s instanceof RunStatus.Completed) completed++;
    }
    assertThat(completed).isEqualTo(5);
  }
}
