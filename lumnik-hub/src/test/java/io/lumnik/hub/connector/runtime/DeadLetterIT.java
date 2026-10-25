package io.lumnik.hub.connector.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.SkipReason;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.repository.ConnectorDeadLetterRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@QuarkusTest
class DeadLetterIT {

  @Inject DeadLetterCapture capture;
  @Inject ConnectorRepository connectors;
  @Inject ConnectorDeadLetterRepository dlqs;
  @Inject ObjectMapper mapper;

  @Test
  @TestTransaction
  void capture_creates_pending_dlq_entry() {
    TenantContext.runAsSystem(() -> {
      Connector c = new Connector();
      c.setTenantId(1L);
      c.setName("dlq-test");
      c.setTypeId("hello-world");
      connectors.persist(c);
      connectors.flush();

      var payload = mapper.createObjectNode().put("seq", 42);
      capture.capture(c.getId(), null, payload,
          new SkipReason.MappingFailed("amount", "negative"));

      var pending = dlqs.findPendingByConnector(c.getId(), 10);
      assertThat(pending).hasSize(1);
      assertThat(pending.get(0).getSkipReason()).isEqualTo("MappingFailed");
      assertThat(pending.get(0).getResolution()).isNull();
    });
  }

  @Test
  @TestTransaction
  void discard_marks_dlq_resolved() {
    TenantContext.runAsSystem(() -> {
      Connector c = new Connector();
      c.setTenantId(1L);
      c.setName("dlq-discard");
      c.setTypeId("hello-world");
      connectors.persist(c);
      connectors.flush();

      var payload = mapper.createObjectNode().put("seq", 1);
      capture.capture(c.getId(), null, payload, new SkipReason.Duplicate("dup-key"));
      var entry = dlqs.findPendingByConnector(c.getId(), 1).get(0);

      capture.discard(entry.getId(), 99L);

      // The close is one conditional bulk update (#1375): it bypasses the session, whose copy is stale.
      dlqs.getEntityManager().refresh(entry);
      var refreshed = dlqs.findById(entry.getId());
      assertThat(refreshed.getResolution()).isEqualTo("DISCARDED");
      assertThat(refreshed.getResolvedBy()).isEqualTo(99L);
    });
  }

  @Test
  @TestTransaction
  void captures_malformed_source_with_reason_name() {
    TenantContext.runAsSystem(() -> {
      Connector c = new Connector();
      c.setTenantId(1L);
      c.setName("dlq-malformed-source");
      c.setTypeId("hello-world");
      connectors.persist(c);
      connectors.flush();

      var payload = mapper.valueToTree(Map.of("file", "/imports/x.csv", "line", 3));
      UUID id = capture.capture(c.getId(), null, payload,
          new SkipReason.MalformedSource("/imports/x.csv", 3L, "col count"));

      var found = dlqs.findById(id);
      assertThat(found.getSkipReason()).isEqualTo("MalformedSource");
      assertThat(found.getErrorMessage()).contains("/imports/x.csv").contains("col count");
    });
  }
}
