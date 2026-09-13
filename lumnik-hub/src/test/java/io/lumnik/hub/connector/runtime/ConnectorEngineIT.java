package io.lumnik.hub.connector.runtime;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;

@QuarkusTest
class ConnectorEngineIT {

    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorRunRepository runs;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject UserTransaction utx;

    @Test
    void run_completes_synchronously_and_writes_history() throws Exception {
        UUID connectorId = TenantContext.callAsSystem(() -> {
            utx.begin();
            Connector c = new Connector();
            c.setTenantId(1L);
            c.setName("engine-test-" + System.nanoTime());
            c.setTypeId("hello-world");
            c.setConfig(Map.of("count", 25));
            connectors.persist(c);
            connectors.flush();
            utx.commit();
            return c.getId();
        });

        RunStatus status = engine.runSync(connectorId, new RunTrigger.Manual(42L));
        assertThat(status).isInstanceOf(RunStatus.Completed.class);
        var completed = (RunStatus.Completed) status;
        assertThat(completed.records()).isEqualTo(25);

        var history = TenantContext.callAsSystem(() -> {
            utx.begin();
            var h = runs.findRecentActiveByConnector(connectorId, 5);
            utx.commit();
            return h;
        });
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getStatus()).isEqualTo("Completed");
        assertThat(history.get(0).getRecordsOut()).isEqualTo(25);

        // The endpoint carries the SAME terminal status as its run row — the column the TUI reads.
        assertThat(reloadEndpointStatus(history.get(0).getEndpointId()))
                .as("endpoint.last_run_status must mirror the run's terminal status")
                .isEqualTo("Completed");
        assertThat(reloadEndpointLastRunAt(history.get(0).getEndpointId()))
                .as("endpoint.last_run_at must be stamped alongside the status")
                .isNotNull();
    }

    @Test
    void failed_run_stamps_the_endpoint_with_Failed() throws Exception {
        UUID connectorId = TenantContext.callAsSystem(() -> {
            utx.begin();
            Connector c = new Connector();
            c.setTenantId(1L);
            c.setName("engine-fail-" + System.nanoTime());
            c.setTypeId("no-such-connector-type");   // registry.find throws → the run fails
            c.setConfig(Map.of());
            connectors.persist(c);
            connectors.flush();
            utx.commit();
            return c.getId();
        });

        RunStatus status = engine.runSync(connectorId, new RunTrigger.Manual(42L));
        assertThat(status).isInstanceOf(RunStatus.Failed.class);

        var history = TenantContext.callAsSystem(() -> {
            utx.begin();
            var h = runs.findRecentActiveByConnector(connectorId, 5);
            utx.commit();
            return h;
        });
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getStatus()).isEqualTo("Failed");

        assertThat(reloadEndpointStatus(history.get(0).getEndpointId()))
                .as("endpoint.last_run_status must mirror the run's terminal status")
                .isEqualTo("Failed");
        assertThat(reloadEndpointLastRunAt(history.get(0).getEndpointId()))
                .as("endpoint.last_run_at must be stamped alongside the status")
                .isNotNull();
    }

    /** Reads the endpoint's persisted status, clearing the L1 cache so the committed row is read. */
    private String reloadEndpointStatus(UUID endpointId) {
        return reloadEndpoint(endpointId, ConnectorEndpoint::getLastRunStatus);
    }

    /** Reads the endpoint's persisted last-run timestamp, past the L1 cache. */
    private Instant reloadEndpointLastRunAt(UUID endpointId) {
        return reloadEndpoint(endpointId, ConnectorEndpoint::getLastRunAt);
    }

    private <T> T reloadEndpoint(UUID endpointId, Function<ConnectorEndpoint, T> read) {
        return TenantContext.callAsSystem(() -> {
            utx.begin();
            endpoints.getEntityManager().clear();
            var e = endpoints.findById(endpointId);
            T v = e == null ? null : read.apply(e);
            utx.commit();
            return v;
        });
    }
}
