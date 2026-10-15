package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.runtime.QueuedRunFixtures.RunRow;
import io.lumnik.hub.connector.runtime.QueuedRunFixtures.Seeded;
import io.lumnik.hub.event.consumer.SchedulerPausedProfile;
import io.lumnik.hub.run.RunLock;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

@QuarkusTest
@TestProfile(SchedulerPausedProfile.class)
class ConnectorRunDispatcherIT {

    @Inject ConnectorRunDispatcher dispatcher;
    @Inject MeterRegistry registry;
    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject UserTransaction utx;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    private int permitsAtRest;

    @BeforeEach
    void clean() {
        QueuedRunFixtures.clearQueue(adminDs);
        permitsAtRest = engine.scheduledRunPermits().availablePermits();
    }

    @AfterEach
    void restore() {
        QueuedRunFixtures.clearQueue(adminDs);
        dispatcher.afterCommit(() -> {});
        await().atMost(Duration.ofSeconds(30)).until(() -> engine.scheduledRunPermits().availablePermits() == permitsAtRest);
    }

    private UUID queueFor(Seeded s, int i, Instant queuedAt) throws Exception {
        String witness = QueuedRunFixtures.witness(connectors, endpoints, utx, s.connectorId(), s.endpointIds().get(i));
        return QueuedRunFixtures.queue(adminDs, s.connectorId(), s.endpointIds().get(i), witness, queuedAt);
    }

    private RunRow settled(UUID run) {
        await().atMost(Duration.ofSeconds(60)).until(() -> {
            String st = QueuedRunFixtures.row(adminDs, run).status();
            return !st.equals("Queued") && !st.equals("Running");
        });
        return QueuedRunFixtures.row(adminDs, run);
    }

    private boolean lockIsFree(UUID endpointId) throws Exception {
        RunLock lock = RunLock.tryAcquire(adminDs, endpointId);
        if (lock == null) return false;
        lock.close();
        return true;
    }

    @Test
    void a_queued_run_is_started_and_completes_and_gives_back_its_lock_and_permit() throws Exception {
        Seeded s = QueuedRunFixtures.csvConnector(connectors, endpoints, utx, 1);
        Instant accepted = Instant.now().minusSeconds(5);
        UUID run = queueFor(s, 0, accepted);

        assertThat(dispatcher.dispatch()).isEqualTo(1);

        RunRow row = settled(run);
        assertThat(row.status()).isEqualTo("Completed");
        assertThat(row.recordsOut()).isEqualTo(3);
        assertThat(row.startedAt()).as("set once, on leaving the queue").isAfterOrEqualTo(accepted);
        assertThat(row.queuedAt()).isCloseTo(accepted, within(1, ChronoUnit.MILLIS));
        await().atMost(Duration.ofSeconds(30)).until(() -> lockIsFree(s.endpointIds().get(0)));
    }

    @Test
    void the_oldest_queued_run_starts_first() throws Exception {
        Seeded s = QueuedRunFixtures.csvConnector(connectors, endpoints, utx, 2);
        UUID newer = queueFor(s, 0, Instant.now().minusSeconds(10));
        UUID older = queueFor(s, 1, Instant.now().minusSeconds(60));
        Semaphore permits = engine.scheduledRunPermits();
        int held = permits.drainPermits();
        permits.release(1);                       // exactly one start is possible
        try {
            assertThat(dispatcher.dispatch()).isEqualTo(1);
            assertThat(settled(older).status()).isEqualTo("Completed");
            assertThat(QueuedRunFixtures.row(adminDs, newer).status()).isEqualTo("Queued");
        } finally {
            await().atMost(Duration.ofSeconds(30)).until(() -> permits.availablePermits() == 1);
            permits.drainPermits();
            permits.release(held);
        }
    }

    @Test
    void a_poll_starts_no_more_than_the_permits_allow_and_leaves_the_rest_queued() throws Exception {
        Seeded s = QueuedRunFixtures.csvConnector(connectors, endpoints, utx, 3);
        List<UUID> runs = new ArrayList<>();
        for (int i = 0; i < 3; i++) runs.add(queueFor(s, i, Instant.now().minusSeconds(30 - i)));
        Semaphore permits = engine.scheduledRunPermits();
        int held = permits.drainPermits();
        permits.release(2);
        try {
            assertThat(dispatcher.dispatch()).isEqualTo(2);
            assertThat(settled(runs.get(0)).status()).isEqualTo("Completed");
            assertThat(settled(runs.get(1)).status()).isEqualTo("Completed");
            assertThat(QueuedRunFixtures.row(adminDs, runs.get(2)).status()).as("no permit left for it").isEqualTo("Queued");
        } finally {
            await().atMost(Duration.ofSeconds(30)).until(() -> permits.availablePermits() == 2);
            permits.drainPermits();
            permits.release(held);
        }
    }

    @Test
    void a_busy_lock_leaves_the_row_queued_and_the_rows_behind_it_still_start() throws Exception {
        Seeded s = QueuedRunFixtures.csvConnector(connectors, endpoints, utx, 2);
        UUID blocked = queueFor(s, 0, Instant.now().minusSeconds(60));
        UUID behind = queueFor(s, 1, Instant.now().minusSeconds(30));

        RunLock held = RunLock.tryAcquire(adminDs, s.endpointIds().get(0));   // a previous run's worker has not closed it yet
        try {
            assertThat(dispatcher.dispatch()).as("the head row's lock is busy; the one behind it starts").isEqualTo(1);
            assertThat(settled(behind).status()).isEqualTo("Completed");
            assertThat(QueuedRunFixtures.row(adminDs, blocked).status()).as("not a verdict: it stays queued").isEqualTo("Queued");
        } finally {
            held.close();
        }

        assertThat(dispatcher.dispatch()).isEqualTo(1);
        assertThat(settled(blocked).status()).isEqualTo("Completed");
    }

    @Test
    void a_definition_reapplied_after_the_commit_does_not_change_what_runs() throws Exception {
        Seeded s = QueuedRunFixtures.csvConnector(connectors, endpoints, utx, 1);
        UUID run = queueFor(s, 0, Instant.now().minusSeconds(5));
        Path other = Files.createTempFile("queued_run_other_", ".csv");
        Files.writeString(other, "row_id,label\n1,a\n2,b\n3,c\n4,d\n5,e\n");
        other.toFile().deleteOnExit();

        // The window between the commit of Queued → Running and the hand-over: a re-apply lands here.
        AtomicBoolean reapplied = new AtomicBoolean();
        dispatcher.afterCommit(() -> {
            Jdbc.update(adminDs, "re-apply", "UPDATE connector.connector_endpoint "
                + "SET path = ?, config = jsonb_set(config, '{transport,path}', to_jsonb(?::text)) WHERE id = ?",
                ps -> { ps.setString(1, other.toString()); ps.setString(2, other.toString());
                        ps.setObject(3, s.endpointIds().get(0)); });
            reapplied.set(true);
        });

        assertThat(dispatcher.dispatch()).isEqualTo(1);

        assertThat(reapplied).as("the re-apply really landed in the window: without this the test could pass untouched").isTrue();
        RunRow row = settled(run);
        assertThat(row.status()).isEqualTo("Completed");
        assertThat(row.recordsOut()).as("the file that was accepted (3 rows), not the re-applied one (5)").isEqualTo(3);
    }

    private void assertEndedUnrun(UUID run, String reasonPart, Seeded s) throws Exception {
        RunRow row = QueuedRunFixtures.row(adminDs, run);
        assertThat(row.status()).isEqualTo("Cancelled");
        assertThat(row.errorMessage()).contains(reasonPart);
        assertThat(row.resolutionExempt()).as("it never ran: nothing to resolve").isTrue();
        assertThat(row.startedAt()).as("never left NULL: it would sort above every recent run").isNotNull();
        assertThat(row.startedAt()).isEqualTo(row.endedAt());
        QueuedRunFixtures.Stamp stamp = QueuedRunFixtures.stamp(adminDs, s.endpointIds().get(0));
        assertThat(stamp.status()).as("finalizeRun's invariant: the endpoint shows the ending with its run").isEqualTo("Cancelled");
        assertThat(stamp.at()).isEqualTo(row.endedAt());
        assertThat(lockIsFree(s.endpointIds().get(0))).as("the lock it took to check was given back").isTrue();
        assertThat(engine.scheduledRunPermits().availablePermits()).isEqualTo(permitsAtRest);
    }

    @Test
    void a_disabled_endpoint_ends_its_queued_run_with_a_reason() throws Exception {
        Seeded s = QueuedRunFixtures.csvConnector(connectors, endpoints, utx, 1);
        UUID run = queueFor(s, 0, Instant.now().minusSeconds(5));
        Jdbc.update(adminDs, "disable", "UPDATE connector.connector_endpoint SET enabled = false WHERE id = ?",
            ps -> ps.setObject(1, s.endpointIds().get(0)));

        assertThat(dispatcher.dispatch()).isZero();

        assertEndedUnrun(run, "endpoint was disabled or deleted", s);
    }

    @Test
    void a_soft_deleted_endpoint_ends_its_queued_run_with_a_reason() throws Exception {
        Seeded s = QueuedRunFixtures.csvConnector(connectors, endpoints, utx, 1);
        UUID run = queueFor(s, 0, Instant.now().minusSeconds(5));
        Jdbc.update(adminDs, "soft-delete", "UPDATE connector.connector_endpoint SET deleted_at = now() WHERE id = ?",
            ps -> ps.setObject(1, s.endpointIds().get(0)));

        assertThat(dispatcher.dispatch()).isZero();

        assertEndedUnrun(run, "endpoint was disabled or deleted", s);
    }

    /** A queued row whose tenant is not its endpoint's (a bug or a repair insert) must never start another tenant's work. */
    @Test
    void a_queued_run_whose_tenant_is_not_its_endpoints_is_ended_not_started() throws Exception {
        Seeded s = QueuedRunFixtures.csvConnector(connectors, endpoints, utx, 1);
        UUID run = queueFor(s, 0, Instant.now().minusSeconds(5));
        Jdbc.update(adminDs, "mismatch", "UPDATE connector.connector_run SET tenant_id = 2 WHERE id = ?",
            ps -> ps.setObject(1, run));

        assertThat(dispatcher.dispatch()).isZero();

        RunRow row = QueuedRunFixtures.row(adminDs, run);
        assertThat(row.status()).isEqualTo("Cancelled");
        assertThat(row.errorMessage()).contains("belongs to another tenant");
        assertThat(row.startedAt()).isNotNull().isEqualTo(row.endedAt());
        assertThat(QueuedRunFixtures.stamp(adminDs, s.endpointIds().get(0)).status())
            .as("the other tenant's endpoint is not stamped").isNull();
        assertThat(lockIsFree(s.endpointIds().get(0))).isTrue();
    }

    @Test
    void a_paused_connector_ends_its_queued_run_with_a_reason() throws Exception {
        Seeded s = QueuedRunFixtures.csvConnector(connectors, endpoints, utx, 1);
        UUID run = queueFor(s, 0, Instant.now().minusSeconds(5));
        Jdbc.update(adminDs, "pause", "UPDATE connector.connector SET enabled = false WHERE id = ?",
            ps -> ps.setObject(1, s.connectorId()));

        assertThat(dispatcher.dispatch()).isZero();

        assertEndedUnrun(run, "connector was paused or deleted", s);
    }

    @Test
    void a_reapplied_definition_ends_its_queued_run_with_a_reason() throws Exception {
        Seeded s = QueuedRunFixtures.csvConnector(connectors, endpoints, utx, 1);
        UUID run = queueFor(s, 0, Instant.now().minusSeconds(5));
        Jdbc.update(adminDs, "re-apply", "UPDATE connector.connector_endpoint "
            + "SET config = jsonb_set(config, '{name}', '\"renamed\"') WHERE id = ?", ps -> ps.setObject(1, s.endpointIds().get(0)));

        assertThat(dispatcher.dispatch()).isZero();

        assertEndedUnrun(run, "definition was re-applied", s);
    }

    @Test
    void a_run_queued_without_a_definition_witness_is_ended_never_started_unverified() throws Exception {
        Seeded s = QueuedRunFixtures.csvConnector(connectors, endpoints, utx, 1);
        UUID run = QueuedRunFixtures.queue(adminDs, s.connectorId(), s.endpointIds().get(0), null, Instant.now().minusSeconds(5));

        assertThat(dispatcher.dispatch()).isZero();

        assertEndedUnrun(run, "without a definition witness", s);
    }

    @Test
    void an_expired_row_is_cancelled_and_a_fresh_one_is_not() throws Exception {
        Seeded s = QueuedRunFixtures.csvConnector(connectors, endpoints, utx, 2);
        UUID stale = queueFor(s, 0, Instant.now().minus(Duration.ofHours(2)));
        UUID fresh = queueFor(s, 1, Instant.now().minusSeconds(5));

        assertThat(dispatcher.sweepExpired(Instant.now(), Duration.ofHours(1))).isEqualTo(1);

        assertEndedUnrun(stale, "expired in the queue", s);
        assertThat(QueuedRunFixtures.row(adminDs, fresh).status()).isEqualTo("Queued");
        assertThat(QueuedRunFixtures.stamp(adminDs, s.endpointIds().get(1)).status())
            .as("the fresh run's endpoint is untouched").isNull();
    }

    /** An attempt that throws (injected through the after-commit hook) must not abort the poll or leak its lock. */
    @Test
    void an_attempt_that_throws_does_not_hold_back_the_rows_behind_it() throws Exception {
        Seeded s = QueuedRunFixtures.csvConnector(connectors, endpoints, utx, 2);
        UUID first = queueFor(s, 0, Instant.now().minusSeconds(60));
        UUID second = queueFor(s, 1, Instant.now().minusSeconds(30));
        AtomicBoolean thrown = new AtomicBoolean();
        dispatcher.afterCommit(() -> {
            if (thrown.compareAndSet(false, true)) throw new IllegalStateException("injected");
        });

        assertThat(dispatcher.dispatch()).as("the poll went on past the attempt that threw").isEqualTo(1);

        assertThat(thrown).isTrue();
        assertThat(settled(second).status()).isEqualTo("Completed");
        assertThat(lockIsFree(s.endpointIds().get(0))).as("the failed attempt gave its lock back").isTrue();
        // What the throw left behind is a Running row with a free lock: exactly what the reaper collects.
        assertThat(QueuedRunFixtures.row(adminDs, first).status()).isEqualTo("Running");
    }

    @Test
    void with_the_flag_off_a_poll_still_drains_what_the_flag_left_queued() throws Exception {
        Seeded s = QueuedRunFixtures.csvConnector(connectors, endpoints, utx, 2);   // one queued row per endpoint (V68)
        UUID run = queueFor(s, 0, Instant.now().minusSeconds(5));
        UUID stale = queueFor(s, 1, Instant.now().minus(Duration.ofHours(3)));      // older than the 1 h max-age: expired, not started

        dispatcher.poll();   // lumnik.run-queue.enabled defaults to false: the queue was switched off with rows in it

        assertThat(QueuedRunFixtures.row(adminDs, stale).status()).as("past max-age: ended").isEqualTo("Cancelled");
        await().atMost(Duration.ofSeconds(60)).until(() -> QueuedRunFixtures.row(adminDs, run).status().equals("Completed"));
        // Nothing is stranded: the endpoint whose queued run expired accepts a new run, and it really runs.
        UUID next = engine.submitRun(s.connectorId(), new RunTrigger.Manual(1L)).get(1);
        await().atMost(Duration.ofSeconds(60)).until(() -> QueuedRunFixtures.row(adminDs, next).status().equals("Completed"));
    }

    @Test
    void with_the_flag_off_and_nothing_queued_a_poll_touches_nothing() throws Exception {
        Seeded s = QueuedRunFixtures.csvConnector(connectors, endpoints, utx, 1);

        assertThat(dispatcher.dispatch()).isZero();
        dispatcher.poll();

        assertThat(Jdbc.queryOne(adminDs, "runs", "SELECT count(*) FROM connector.connector_run WHERE connector_id = ?",
            ps -> ps.setObject(1, s.connectorId()), rs -> rs.getLong(1)).orElseThrow()).isZero();
    }

    private double cancelled() {
        return registry.counter("lumnik_runs_total", "status", "Cancelled").count();
    }

    @Test
    void an_expired_queued_run_is_counted_as_a_cancelled_run() throws Exception {
        Seeded s = QueuedRunFixtures.csvConnector(connectors, endpoints, utx, 1);
        queueFor(s, 0, Instant.now().minus(Duration.ofHours(3)));
        double before = cancelled();

        assertThat(dispatcher.sweepExpired(Instant.now(), Duration.ofHours(1))).isEqualTo(1);

        assertThat(cancelled() - before).isEqualTo(1.0);
    }

    @Test
    void a_queued_run_ended_by_the_start_check_is_counted_as_a_cancelled_run() throws Exception {
        Seeded s = QueuedRunFixtures.csvConnector(connectors, endpoints, utx, 1);
        UUID run = QueuedRunFixtures.queue(adminDs, s.connectorId(), s.endpointIds().get(0), "not-the-hash", Instant.now());
        double before = cancelled();

        dispatcher.dispatch();

        assertThat(QueuedRunFixtures.row(adminDs, run).status()).isEqualTo("Cancelled");
        assertThat(cancelled() - before).isEqualTo(1.0);
    }
}
