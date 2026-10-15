package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.runtime.QueuedRunFixtures.Seeded;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.awaitility.Awaitility.await;

@QuarkusTest
@TestProfile(RunQueueEnabledProfile.class)
class ConnectorRunDispatcherEnabledIT {

    @Inject ConnectorRunDispatcher dispatcher;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject UserTransaction utx;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @BeforeEach
    @AfterEach
    void clean() {
        QueuedRunFixtures.clearQueue(adminDs);
    }

    private UUID queueFor(Seeded s, int i, Instant queuedAt) throws Exception {
        String witness = QueuedRunFixtures.witness(connectors, endpoints, utx, s.connectorId(), s.endpointIds().get(i));
        return QueuedRunFixtures.queue(adminDs, s.connectorId(), s.endpointIds().get(i), witness, queuedAt);
    }

    @Test
    void a_poll_with_the_flag_on_starts_a_queued_run() throws Exception {
        Seeded s = QueuedRunFixtures.csvConnector(connectors, endpoints, utx, 1);
        UUID run = queueFor(s, 0, Instant.now().minusSeconds(5));

        dispatcher.poll();

        await().atMost(Duration.ofSeconds(60)).until(() -> QueuedRunFixtures.row(adminDs, run).status().equals("Completed"));
    }

    @Test
    void a_wake_up_starts_a_queued_run_without_waiting_for_a_poll() throws Exception {
        Seeded s = QueuedRunFixtures.csvConnector(connectors, endpoints, utx, 1);
        UUID run = queueFor(s, 0, Instant.now().minusSeconds(5));

        dispatcher.wake();

        await().atMost(Duration.ofSeconds(60)).until(() -> QueuedRunFixtures.row(adminDs, run).status().equals("Completed"));
    }
}
