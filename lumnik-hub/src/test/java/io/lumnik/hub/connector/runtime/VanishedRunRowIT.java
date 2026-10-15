package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The run row is read once, at the top of the chunk loop, and it can be gone: deleting a connector
 * removes its runs (V26), the reaper clears rows at boot, and both race a run that is already
 * under way. {@code ConnectorEngine.runChunks} dereferenced that read with no guard.
 *
 * <p>What followed is the defect {@code ChunkExecutor} carried one layer down until #560 — the same
 * shape, copied. The NPE escaped the chunk loop, and from there:
 * <ul>
 *   <li>on the SYNCHRONOUS entry point ({@code runSyncEndpoint}, and {@code runSync} above it)
 *       nothing catches it at all: the exception reaches the caller and the run row — when there
 *       is one — is left {@code Running} until the next boot;</li>
 *   <li>on the async worker it lands in {@code executeEndpointRun}'s last-resort catch, which
 *       finalizes the row {@code Failed} while bypassing {@code failRun}, so no
 *       {@code connector.run.failed} event is ever emitted and every watcher sees the run go
 *       silent.</li>
 * </ul>
 *
 * <p>A run whose row is gone is a refusal, not a crash, and it has the sentence
 * {@code executeChunkManaged} already writes for the identical read one level down.
 */
@QuarkusTest
class VanishedRunRowIT {

    private static final long TENANT = 1L;

    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @Test
    void a_vanished_run_row_ends_the_run_instead_of_reaching_the_caller() {
        UUID endpointId = seedEndpoint();
        UUID vanished = UUID.randomUUID();

        RunStatus status = engine.runSyncEndpoint(endpointId, vanished);

        assertThat(status)
            .as("a run row that is not there is a refusal the engine reports, not an exception"
                + " it throws at whoever asked for the run")
            .isInstanceOf(RunStatus.Failed.class);
        assertThat(((RunStatus.Failed) status).message())
            .as("the refusal names what could not be found, as executeChunkManaged already does")
            .isEqualTo("Run not found: " + vanished);
    }

    @Test
    void a_vanished_run_row_still_announces_the_failure() {
        UUID endpointId = seedEndpoint();
        UUID vanished = UUID.randomUUID();

        engine.runSyncEndpoint(endpointId, vanished);

        assertThat(failedEventsFor(vanished))
            .as("every ending goes through failRun — the event is what lm, the TUI and every"
                + " webhook subscriber read, and a run that ends without one goes silent")
            .isEqualTo(1);
    }

    /**
     * Read on the admin datasource, which bypasses RLS — so the tenant is a condition here rather
     * than something the connection decides. That is the point: the funnel emits under the tenant
     * the run belongs to, and an event attributed to anyone else would read exactly like a working
     * one from inside that tenant's own session.
     */
    private int failedEventsFor(UUID runId) {
        return Jdbc.queryOne(adminDs, "read the outbox",
                "SELECT count(*) FROM core.event_outbox WHERE event_type = 'connector.run.failed'"
                    + " AND tenant_id = ? AND payload->>'run_id' = ?",
                ps -> {
                    ps.setLong(1, TENANT);
                    ps.setString(2, runId.toString());
                },
                rs -> rs.getInt(1))
            // A count always has a row; an empty Optional here would mean the driver, not the data.
            .orElseThrow();
    }

    /** A connector whose type the registry knows, so the run reaches the chunk loop. */
    private UUID seedEndpoint() {
        return TenantContext.callAsSystem(() -> QuarkusTransaction.requiringNew().call(() -> {
            Connector c = new Connector();
            c.setTenantId(TENANT);
            c.setName("vanished-run-" + UUID.randomUUID());
            c.setTypeId("hello-world");
            c.setConfig(Map.of());
            connectors.persist(c);
            connectors.flush();

            ConnectorEndpoint ep = new ConnectorEndpoint();
            ep.setTenantId(TENANT);
            ep.setConnectorId(c.getId());
            ep.setName("default");
            ep.setMethod("GET");
            ep.setPath("/default");
            ep.setEnabled(true);
            ep.setConfig(Map.of());
            endpoints.persist(ep);
            endpoints.flush();

            return ep.getId();
        }));
    }
}
