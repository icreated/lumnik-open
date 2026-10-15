package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.event.consumer.SchedulerPausedProfile;
import io.lumnik.hub.run.RunLock;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static io.lumnik.hub.testsupport.EventItSupport.runInTxAsSystem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * #897: every endpoint run holds an {@code admin} connection for its whole life (its {@link RunLock})
 * and its chunks borrow another from the SAME pool. A scheduled fan-out with more endpoints than the
 * pool has slots was a hold-and-wait: the locks filled the pool, the chunk writes timed out, and the
 * endpoints started last failed with "advisory lock error".
 *
 * <p>The pool is shrunk to four slots and the connector given six endpoints — the shape of a
 * 25-table {@code jdbc-generic} manifest against the default pool of 20, at a size a test can
 * afford — and the bound to two runs, two slots each. The scheduler is off so nothing else borrows
 * from the pool, or takes a permit, while a fan-out runs.
 */
@QuarkusTest
@TestProfile(EndpointRunConcurrencyBoundIT.SmallAdminPool.class)
class EndpointRunConcurrencyBoundIT {

    private static final int ENDPOINTS = 6;
    private static final int PERMITS = 2;
    private static final RunTrigger SCHEDULE = new RunTrigger.Schedule("* * * * *");

    public static class SmallAdminPool extends SchedulerPausedProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.put("quarkus.datasource.\"admin\".jdbc.max-size", "4");
            overrides.put("lumnik.connector.max-concurrent-scheduled-runs", Integer.toString(PERMITS));
            return overrides;
        }
    }

    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject UserTransaction utx;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @Test
    void a_fan_out_wider_than_the_admin_pool_completes_every_endpoint() throws Exception {
        UUID connectorId = csvConnectorWithEndpoints(ENDPOINTS);

        // ConnectorScheduler's own call: the scheduled fan-out.
        engine.submitAsync(connectorId, SCHEDULE).get(3, TimeUnit.MINUTES);

        assertThat(outcomes(connectorId))
            .as("one run per endpoint, and none of them failed because the pool was full")
            .hasSize(ENDPOINTS)
            .containsOnly("Completed");
        assertThat(engine.scheduledRunPermits().availablePermits()).as("every permit came back").isEqualTo(PERMITS);
    }

    @Test
    void an_endpoint_waiting_for_a_permit_has_no_run_row_until_it_gets_one() throws Exception {
        UUID connectorId = csvConnectorWithEndpoints(1);
        Semaphore permits = engine.scheduledRunPermits();
        int held = permits.drainPermits();
        assertThat(held).isEqualTo(PERMITS);
        Future<RunStatus> fanOut;
        try {
            fanOut = engine.submitAsync(connectorId, SCHEDULE);
            await().atMost(Duration.ofSeconds(30)).until(permits::hasQueuedThreads);   // the fan-out waits

            // Not a Running row with no lock behind it: RunReaper would fail that one on its next tick.
            assertThat(outcomes(connectorId)).as("no row while the endpoint waits").isEmpty();
            assertThat(engine.hasPendingFanOut(connectorId)).isTrue();
        } finally {
            permits.release(held);
        }

        assertThat(fanOut.get(1, TimeUnit.MINUTES)).isInstanceOf(RunStatus.Completed.class);
        assertThat(outcomes(connectorId)).containsExactly("Completed");
        assertThat(engine.hasPendingFanOut(connectorId)).isFalse();
        assertThat(permits.availablePermits()).isEqualTo(PERMITS);
    }

    @Test
    void an_interrupted_wait_writes_no_row_and_keeps_no_permit() throws Exception {
        UUID connectorId = csvConnectorWithEndpoints(1);
        Semaphore permits = engine.scheduledRunPermits();
        int held = permits.drainPermits();
        try {
            Future<RunStatus> fanOut = engine.submitAsync(connectorId, SCHEDULE);
            await().atMost(Duration.ofSeconds(30)).until(permits::hasQueuedThreads);   // the fan-out waits

            fanOut.cancel(true);   // what onStop's shutdownNow does to it at hub shutdown
            await().atMost(Duration.ofSeconds(30)).until(() -> !engine.hasPendingFanOut(connectorId));
        } finally {
            permits.release(held);
        }

        assertThat(outcomes(connectorId)).as("an endpoint that never got its permit never got a row").isEmpty();
        assertThat(permits.availablePermits()).isEqualTo(PERMITS);
    }

    @Test
    void a_connector_paused_while_its_endpoint_waited_starts_nothing() throws Exception {
        UUID connectorId = csvConnectorWithEndpoints(1);
        Semaphore permits = engine.scheduledRunPermits();
        int held = permits.drainPermits();
        Future<RunStatus> fanOut;
        try {
            fanOut = engine.submitAsync(connectorId, SCHEDULE);
            await().atMost(Duration.ofSeconds(30)).until(permits::hasQueuedThreads);   // the fan-out waits
            Jdbc.update(adminDs, "pause connector", "UPDATE connector.connector SET enabled = false WHERE id = ?",
                ps -> ps.setObject(1, connectorId));
        } finally {
            permits.release(held);
        }

        fanOut.get(1, TimeUnit.MINUTES);
        assertThat(outcomes(connectorId)).as("resolved before the pause, started after it: not at all").isEmpty();
        assertThat(permits.availablePermits()).isEqualTo(PERMITS);
    }

    @Test
    void an_endpoint_run_while_it_waited_is_not_run_again_for_the_same_slot() throws Exception {
        UUID connectorId = csvConnectorWithEndpoints(1);
        Semaphore permits = engine.scheduledRunPermits();
        int held = permits.drainPermits();
        Future<RunStatus> fanOut;
        try {
            fanOut = engine.submitAsync(connectorId, SCHEDULE);
            await().atMost(Duration.ofSeconds(30)).until(permits::hasQueuedThreads);   // the fan-out waits
            // A manual run takes no permit: it runs the endpoint while the scheduled one waits — as
            // another replica's fan-out for the same slot would, having seen no row yet.
            assertThat(engine.runSync(connectorId, new RunTrigger.Manual(1L))).isInstanceOf(RunStatus.Completed.class);
        } finally {
            permits.release(held);
        }

        fanOut.get(1, TimeUnit.MINUTES);
        assertThat(outcomes(connectorId)).as("the manual run, and no second one for this slot").containsExactly("Completed");
    }

    @Test
    @Timeout(60)
    void the_manual_door_takes_no_permit() throws Exception {
        UUID connectorId = csvConnectorWithEndpoints(2);
        Semaphore permits = engine.scheduledRunPermits();
        int held = permits.drainPermits();
        try {
            // lm connector run's own call: it would hang here if it waited for the scheduler's bound.
            assertThat(engine.submitRun(connectorId, new RunTrigger.Manual(1L))).hasSize(2);
            await().atMost(Duration.ofSeconds(30)).until(() -> outcomes(connectorId).equals(List.of("Completed", "Completed")));
        } finally {
            permits.release(held);
        }
    }

    private List<String> outcomes(UUID connectorId) {
        return Jdbc.query(adminDs, "EndpointRunConcurrencyBoundIT runs",
            "SELECT status || COALESCE(' — ' || error_message, '') FROM connector.connector_run WHERE connector_id = ?",
            ps -> ps.setObject(1, connectorId), rs -> rs.getString(1));
    }

    private UUID csvConnectorWithEndpoints(int count) throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        Path dir = Files.createTempDirectory("run_bound_it_");
        dir.toFile().deleteOnExit();
        return runInTxAsSystem(utx, () -> {
            Connector c = new Connector();
            c.setTenantId(1L);
            c.setName("run-bound-" + suffix);
            c.setTypeId("csv-file");
            c.setConfig(Map.of("kind", "csv-file", "parser", Map.of("has_header", true)));
            connectors.persist(c);
            connectors.flush();
            for (int i = 0; i < count; i++) {
                Path file = dir.resolve("e" + i + ".csv");
                // Not "id": it collides with DynamicTableWriter's own BIGSERIAL column.
                Files.writeString(file, "row_id,label\n1,a\n2,b\n3,c\n");
                file.toFile().deleteOnExit();
                ConnectorEndpoint ep = new ConnectorEndpoint();
                ep.setTenantId(1L);
                ep.setConnectorId(c.getId());
                ep.setName("e" + i);
                ep.setMethod("GET");
                ep.setPath(file.toString());
                ep.setEnabled(true);
                // The endpoint's config is laid over the connector's (EffectiveConfig): one file each.
                ep.setConfig(Map.of(
                    "name", "rb" + i + "_" + suffix,
                    "transport", Map.of("kind", "local", "path", file.toString())));
                endpoints.persist(ep);
            }
            endpoints.flush();
            return c.getId();
        });
    }
}
