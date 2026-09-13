package io.lumnik.hub.connector.runtime;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.*;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.connector.registry.ConnectorTypeRegistry;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@QuarkusTest
class ChunkExecutorIT {

  @Inject ChunkExecutor exec;
  @Inject ConnectorTypeRegistry registry;
  @Inject ConnectorRepository connectors;
  @Inject ConnectorEndpointRepository endpoints;
  @Inject ConnectorRunRepository runs;

  @Test
  @TestTransaction
  void executes_one_chunk_of_hello_world() {
    TenantContext.runAsSystem(() -> {
      Connector c = new Connector();
      c.setTenantId(1L);
      c.setName("chunk-test");
      c.setTypeId("hello-world");
      c.setConfig(Map.of("count", 5));
      connectors.persist(c);
      connectors.flush();

      ConnectorEndpoint endpoint = new ConnectorEndpoint();
      endpoint.setTenantId(1L);
      endpoint.setConnectorId(c.getId());
      endpoint.setName("default");
      endpoint.setMethod("GET");
      endpoint.setPath("/default");
      endpoint.setEnabled(true);
      endpoint.setConfig(Map.of("count", 5));
      endpoints.persist(endpoint);
      endpoints.flush();

      ConnectorRun run = new ConnectorRun();
      run.setTenantId(1L);
      run.setConnectorId(c.getId());
      run.setEndpointId(endpoint.getId());
      run.setStatus("Running");
      run.setSyncMode("Incremental");
      run.setStartedAt(Instant.now());
      run.setTriggeredBy("MANUAL");
      runs.persist(run);
      runs.flush();

      var type = registry.find("hello-world");
      var result = exec.executeChunk(run, type, endpoint, ConnectorCursor.empty(), null);

      assertThat(result).isInstanceOf(ChunkResult.Success.class);
      var s = (ChunkResult.Success) result;
      assertThat(s.processed()).isEqualTo(5);   // count=5, chunkSize 500 → all in one chunk
    });
  }
}
