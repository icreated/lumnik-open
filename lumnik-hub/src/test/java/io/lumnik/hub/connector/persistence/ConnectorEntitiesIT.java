package io.lumnik.hub.connector.persistence;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.SkipPolicy;
import io.lumnik.hub.connector.api.types.SyncMode;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.TestTransaction;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import static org.assertj.core.api.Assertions.*;

@QuarkusTest
class ConnectorEntitiesIT {

  @Inject ConnectorRepository repo;

  @Test
  @TestTransaction
  void connector_persist_and_load_round_trip() {
    TenantContext.runAsSystem(() -> {
      Connector c = new Connector();
      c.setTenantId(1L);
      c.setName("test-connector");
      c.setTypeId("hello-world");
      var cfg = new HashMap<String, Object>();
      cfg.put("count", 100);
      c.setConfig(cfg);
      c.setSyncMode(new SyncMode.Incremental());
      c.setSkipPolicy(new SkipPolicy.SkipUpTo(50));
      c.setEnabled(true);
      repo.persist(c);
      repo.flush();

      Connector loaded = repo.findById(c.getId());
      assertThat(loaded.getName()).isEqualTo("test-connector");
      assertThat(loaded.getTypeId()).isEqualTo("hello-world");
      assertThat(loaded.getSyncMode()).isInstanceOf(SyncMode.Incremental.class);
      assertThat(loaded.getSkipPolicy()).isInstanceOf(SkipPolicy.SkipUpTo.class);
      assertThat(((SkipPolicy.SkipUpTo) loaded.getSkipPolicy()).maxSkips()).isEqualTo(50);
      assertThat(loaded.getConfig().get("count")).isEqualTo(100);
    });
  }
}
