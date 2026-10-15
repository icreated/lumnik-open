package io.lumnik.hub.connector.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One connector, one endpoint and one {@code Running} run row, committed, for the ITs that drive
 * {@code ConnectorEngine.runChunks} with a type of their own; and a committed read of that row's status back.
 */
final class RunRowFixtures {

    record Seeded(Connector connector, ConnectorEndpoint endpoint, UUID runId) {}

    private final ConnectorRepository connectors;
    private final ConnectorEndpointRepository endpoints;
    private final ConnectorRunRepository runs;

    RunRowFixtures(ConnectorRepository connectors, ConnectorEndpointRepository endpoints, ConnectorRunRepository runs) {
        this.connectors = connectors;
        this.endpoints = endpoints;
        this.runs = runs;
    }

    Seeded seed(String typeId, String namePrefix) {
        return TenantContext.callAsSystem(() -> QuarkusTransaction.requiringNew().call(() -> {
            Connector c = new Connector();
            c.setTenantId(1L);
            c.setName(namePrefix + UUID.randomUUID());
            c.setTypeId(typeId);
            c.setConfig(Map.of());
            connectors.persist(c);
            connectors.flush();

            ConnectorEndpoint ep = new ConnectorEndpoint();
            ep.setTenantId(1L);
            ep.setConnectorId(c.getId());
            ep.setName("default");
            ep.setMethod("GET");
            ep.setPath("/default");
            ep.setEnabled(true);
            ep.setConfig(Map.of());
            endpoints.persist(ep);
            endpoints.flush();

            ConnectorRun r = new ConnectorRun();
            r.setTenantId(1L);
            r.setConnectorId(c.getId());
            r.setEndpointId(ep.getId());
            r.setStatus("Running");
            r.setSyncMode("Incremental");
            r.setStartedAt(Instant.now());
            r.setTriggeredBy("MANUAL");
            runs.persist(r);
            runs.flush();

            return new Seeded(c, ep, r.getId());
        }));
    }

    /** The run row's status as another connection would see it: a fresh transaction, L1 cache cleared first. */
    String persistedStatus(UUID runId) {
        return TenantContext.callAsSystem(() -> QuarkusTransaction.requiringNew().call(() -> {
            runs.getEntityManager().clear();
            return runs.findById(runId).getStatus();
        }));
    }

    static ConnectorCursor cursorAt(long offset) {
        return new ConnectorCursor(new ObjectMapper().createObjectNode().put("offset", offset), Instant.EPOCH);
    }
}
