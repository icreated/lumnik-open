package io.lumnik.hub.connector.domain;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class ConnectorTagsIT {

  @Inject ConnectorRepository connectors;
  @Inject UserTransaction utx;

  @Test
  void connector_persists_and_reads_tags() throws Exception {
    var id = TenantContext.callAsSystem(() -> {
      utx.begin();
      Connector c = new Connector();
      c.setTenantId(1L);
      c.setName("with-tags-" + System.nanoTime());
      c.setTypeId("hello-world");
      c.setConfig(Map.of("count", 1));
      c.setEnabled(true);
      c.setTags(List.of("compta", "paie"));
      connectors.persist(c);
      utx.commit();
      return c.getId();
    });

    TenantContext.runAsSystem(() -> {
      try {
        utx.begin();
        Connector loaded = connectors.findById(id);
        assertThat(loaded.getTags()).containsExactlyInAnyOrder("compta", "paie");
        utx.commit();
      } catch (Exception e) { throw new RuntimeException(e); }
    });
  }

  @Test
  void connector_defaults_tags_to_empty_list() throws Exception {
    var id = TenantContext.callAsSystem(() -> {
      utx.begin();
      Connector c = new Connector();
      c.setTenantId(1L);
      c.setName("no-tags-" + System.nanoTime());
      c.setTypeId("hello-world");
      c.setConfig(Map.of("count", 1));
      c.setEnabled(true);
      connectors.persist(c);
      utx.commit();
      return c.getId();
    });

    TenantContext.runAsSystem(() -> {
      try {
        utx.begin();
        Connector loaded = connectors.findById(id);
        assertThat(loaded.getTags()).isNotNull().isEmpty();
        utx.commit();
      } catch (Exception e) { throw new RuntimeException(e); }
    });
  }
}
