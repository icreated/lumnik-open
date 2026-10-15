package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.runtime.QueuedRunFixtures.Seeded;
import io.lumnik.hub.event.consumer.SchedulerPausedProfile;
import io.lumnik.hub.run.RunLock;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #952 slice 3: the V68 index forbids a second Queued|Running row per endpoint. A new run that meets one ends
 * at birth Cancelled, as it does for a held lock — on the flag-off manual door (a row left by an earlier
 * flag-on period) and in a scheduled fan-out (which then goes on to its other endpoints).
 */
@QuarkusTest
@TestProfile(SchedulerPausedProfile.class)
class InFlightConflictAtBirthIT {

    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject UserTransaction utx;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    private Seeded seeded;

    @AfterEach
    void clean() {
        if (seeded != null) {
            Jdbc.update(adminDs, "clean", "DELETE FROM connector.connector WHERE id = ?", ps -> ps.setObject(1, seeded.connectorId()));
        }
        QueuedRunFixtures.clearQueue(adminDs);
    }

    private UUID leftoverQueued(int endpoint) throws Exception {
        String witness = QueuedRunFixtures.witness(connectors, endpoints, utx, seeded.connectorId(), seeded.endpointIds().get(endpoint));
        return QueuedRunFixtures.queue(adminDs, seeded.connectorId(), seeded.endpointIds().get(endpoint), witness, Instant.now());
    }

    @Test
    void the_flag_off_door_ends_at_birth_against_a_leftover_queued_row() throws Exception {
        seeded = QueuedRunFixtures.csvConnector(connectors, endpoints, utx, 1);
        UUID queued = leftoverQueued(0);

        List<UUID> ids = engine.submitRun(seeded.connectorId(), new RunTrigger.Manual(1L));

        assertThat(ids).hasSize(1);
        var born = QueuedRunFixtures.row(adminDs, ids.get(0));
        assertThat(born.status()).isEqualTo("Cancelled");
        assertThat(born.errorMessage()).contains("another run is already active");
        assertThat(born.startedAt()).as("a run that never ran: zero-length span").isEqualTo(born.endedAt());
        assertThat(born.resolutionExempt()).isTrue();
        assertThat(QueuedRunFixtures.row(adminDs, queued).status()).as("the queued run is untouched").isEqualTo("Queued");
        try (RunLock lock = RunLock.tryAcquire(adminDs, seeded.endpointIds().get(0))) {
            assertThat(lock).as("the attempt gave its lock back").isNotNull();
        }
    }

    @Test
    void a_scheduled_fan_out_goes_on_to_its_other_endpoints() throws Exception {
        seeded = QueuedRunFixtures.csvConnector(connectors, endpoints, utx, 2);
        UUID queued = leftoverQueued(0);

        RunStatus summary = engine.submitAsync(seeded.connectorId(), new RunTrigger.Schedule("0 * * * * ?"))
            .get(60, TimeUnit.SECONDS);   // an escaping violation would surface here as an ExecutionException

        assertThat(summary).isInstanceOf(RunStatus.Cancelled.class);   // the first non-Completed ending
        assertThat(QueuedRunFixtures.row(adminDs, queued).status()).isEqualTo("Queued");
        long completed = Jdbc.queryOne(adminDs, "completed", "SELECT count(*) FROM connector.connector_run "
            + "WHERE endpoint_id = ? AND status = 'Completed'", ps -> ps.setObject(1, seeded.endpointIds().get(1)),
            rs -> rs.getLong(1)).orElseThrow();
        assertThat(completed).as("the second endpoint still ran").isEqualTo(1);
    }
}
