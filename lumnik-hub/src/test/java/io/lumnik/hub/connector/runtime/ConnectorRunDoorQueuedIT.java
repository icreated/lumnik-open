package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.runtime.QueuedRunFixtures.Seeded;
import io.lumnik.hub.run.RunLock;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.lumnik.testkit.auth.TestIdentity;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** #952 slice 3: with lumnik.run-queue.enabled the manual connector door writes Queued rows and starts nothing itself. */
@QuarkusTest
@TestProfile(RunQueueEnabledProfile.class)
class ConnectorRunDoorQueuedIT {

    @Inject ConnectorEngine engine;
    @Inject ConnectorRunDispatcher dispatcher;
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

    private String sha(UUID run) {
        return Jdbc.queryOne(adminDs, "sha", "SELECT definition_sha256 FROM connector.connector_run WHERE id = ?",
            ps -> ps.setObject(1, run), rs -> rs.getString(1)).orElseThrow();
    }

    @Test
    void the_door_writes_a_queued_row_and_starts_nothing() throws Exception {
        seeded = QueuedRunFixtures.csvConnector(connectors, endpoints, utx, 1);

        List<UUID> ids = engine.submitRun(seeded.connectorId(), new RunTrigger.Manual(1L));

        assertThat(ids).hasSize(1);
        var row = QueuedRunFixtures.row(adminDs, ids.get(0));
        assertThat(row.status()).isEqualTo("Queued");
        assertThat(row.startedAt()).as("not started").isNull();
        assertThat(row.endedAt()).isNull();
        assertThat(row.queuedAt()).as("the accept instant").isNotNull();
        assertThat(sha(ids.get(0))).isEqualTo(QueuedRunFixtures.witness(
            connectors, endpoints, utx, seeded.connectorId(), seeded.endpointIds().get(0)));
        try (RunLock lock = RunLock.tryAcquire(adminDs, seeded.endpointIds().get(0))) {
            assertThat(lock).as("a queued run holds no lock").isNotNull();
        }
    }

    @Test
    void every_enabled_endpoint_gets_its_own_queued_row() throws Exception {
        seeded = QueuedRunFixtures.csvConnector(connectors, endpoints, utx, 3);

        List<UUID> ids = engine.submitRun(seeded.connectorId(), new RunTrigger.Manual(1L));

        assertThat(ids).hasSize(3);
        assertThat(ids).allSatisfy(id -> assertThat(QueuedRunFixtures.row(adminDs, id).status()).isEqualTo("Queued"));
        assertThat(ids.stream().map(this::sha).distinct()).as("one witness per endpoint definition").hasSize(3);
    }

    @Test
    void a_second_request_for_a_queued_endpoint_ends_at_birth() throws Exception {
        seeded = QueuedRunFixtures.csvConnector(connectors, endpoints, utx, 1);
        UUID first = engine.submitRun(seeded.connectorId(), new RunTrigger.Manual(1L)).get(0);

        UUID second = engine.submitRun(seeded.connectorId(), new RunTrigger.Manual(1L)).get(0);

        assertThat(QueuedRunFixtures.row(adminDs, first).status()).isEqualTo("Queued");
        var born = QueuedRunFixtures.row(adminDs, second);
        assertThat(born.status()).isEqualTo("Cancelled");
        assertThat(born.errorMessage()).contains("another run is already active");
        assertThat(born.startedAt()).isEqualTo(born.endedAt());
        assertThat(QueuedRunFixtures.stamp(adminDs, seeded.endpointIds().get(0)).status()).isEqualTo("Cancelled");
    }

    @Test
    void a_request_for_a_running_endpoint_ends_at_birth() throws Exception {
        seeded = QueuedRunFixtures.csvConnector(connectors, endpoints, utx, 1);
        UUID running = UUID.randomUUID();
        Jdbc.update(adminDs, "running", "INSERT INTO connector.connector_run (id, tenant_id, connector_id, endpoint_id, status, "
            + "sync_mode, started_at, triggered_by) VALUES (?, 1, ?, ?, 'Running', 'Incremental', now(), 'manual')",
            ps -> { ps.setObject(1, running); ps.setObject(2, seeded.connectorId()); ps.setObject(3, seeded.endpointIds().get(0)); });

        UUID id = engine.submitRun(seeded.connectorId(), new RunTrigger.Manual(1L)).get(0);

        assertThat(QueuedRunFixtures.row(adminDs, id).status()).isEqualTo("Cancelled");
        assertThat(QueuedRunFixtures.row(adminDs, running).status()).isEqualTo("Running");
    }

    /**
     * Eight requests that have ALL passed the pre-check (count == 0) before any of them inserts: the barrier sits in the
     * engine's test seam between the two, so the index — not timing — decides, and every loser reaches the catch.
     */
    @Test
    void simultaneous_requests_leave_exactly_one_queued_row() throws Exception {
        seeded = QueuedRunFixtures.csvConnector(connectors, endpoints, utx, 1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        CyclicBarrier checked = new CyclicBarrier(8);
        engine.beforeQueueInsert(() -> {
            try { checked.await(30, TimeUnit.SECONDS); } catch (Exception e) { throw new IllegalStateException(e); }
        });
        try {
            List<Future<List<UUID>>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return engine.submitRun(seeded.connectorId(), new RunTrigger.Manual(1L));
                }));
            }
            go.countDown();
            List<UUID> all = new ArrayList<>();
            for (Future<List<UUID>> f : futures) all.addAll(f.get(60, TimeUnit.SECONDS));   // a throw fails the test

            assertThat(all).hasSize(8);
            List<String> statuses = all.stream().map(id -> QueuedRunFixtures.row(adminDs, id).status()).toList();
            assertThat(statuses.stream().filter("Queued"::equals).count()).isEqualTo(1);
            assertThat(statuses.stream().filter("Cancelled"::equals).count()).isEqualTo(7);
        } finally {
            engine.beforeQueueInsert(() -> {});
            pool.shutdownNow();
        }
    }

    /** The witness the door stores is the one the dispatcher recomputes: the queued run really starts. */
    @Test
    void a_run_the_door_queued_is_started_by_the_dispatcher() throws Exception {
        seeded = QueuedRunFixtures.csvConnector(connectors, endpoints, utx, 1);
        UUID id = engine.submitRun(seeded.connectorId(), new RunTrigger.Manual(1L)).get(0);

        dispatcher.poll();

        await().atMost(Duration.ofSeconds(60)).until(() -> QueuedRunFixtures.row(adminDs, id).status().equals("Completed"));
        var row = QueuedRunFixtures.row(adminDs, id);
        assertThat(row.queuedAt()).isNotNull();
        assertThat(row.startedAt()).isNotNull();
    }

    @Test
    void the_http_door_answers_202_with_the_queued_runs_and_starts_them_without_a_poll() throws Exception {
        seeded = QueuedRunFixtures.csvConnector(connectors, endpoints, utx, 2);

        List<String> ids = given().headers(TestIdentity.tenantAndUser(1L))
            .when().post("/api/platform/connectors/" + seeded.connectorId() + "/run")
            .then().statusCode(202).body("runIds", hasSize(2)).body("runId", notNullValue())
            .extract().jsonPath().getList("runIds", String.class);

        for (String id : ids) {   // the scheduler is off and nobody polls: only the door's wake-up can start these
            await().atMost(Duration.ofSeconds(60)).until(
                () -> QueuedRunFixtures.row(adminDs, UUID.fromString(id)).status().equals("Completed"));
            assertThat(QueuedRunFixtures.row(adminDs, UUID.fromString(id)).queuedAt()).as("it went through the queue").isNotNull();
        }
    }
}
