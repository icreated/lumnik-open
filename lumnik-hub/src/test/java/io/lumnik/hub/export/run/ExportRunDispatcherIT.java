package io.lumnik.hub.export.run;

import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.run.RunLock;
import io.lumnik.hub.event.consumer.SchedulerPausedProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * #952: the export side of the run queue. A Queued export run belongs to nobody; the dispatcher starts it
 * under the D2 protocol, or leaves it queued (a busy lock), or ends it explicitly (the export is gone or
 * was re-applied; it waited too long), and in every case gives back the lock it took and the permit.
 */
@QuarkusTest
@TestProfile(SchedulerPausedProfile.class)
class ExportRunDispatcherIT extends ExportQueuedRunITSupport {

    @Test
    void a_queued_export_run_is_started_delivers_and_gives_back_its_lock_and_permit() throws Exception {
        String target = unique("queued") + ".csv";
        String name = applyExport(target, false);
        UUID exportId = exportId(name);
        Instant accepted = Instant.now().minusSeconds(5);
        UUID run = queue(name, accepted);

        assertThat(dispatcher.dispatch()).isEqualTo(1);

        QueuedRun row = settled(run);
        assertThat(row.status()).isEqualTo("Completed");
        assertThat(remote(target)).isEqualTo(local().bytes());
        assertThat(row.startedAt()).as("set once, on leaving the queue").isAfterOrEqualTo(accepted);
        assertThat(row.queuedAt()).isCloseTo(accepted, within(1, ChronoUnit.MILLIS));
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> lockIsFree(exportId));
        Awaitility.await().atMost(Duration.ofSeconds(30))
            .until(() -> scheduler.runPermits().availablePermits() == permitsAtRest);
    }

    @Test
    void the_oldest_queued_export_run_starts_first() throws Exception {
        String newerName = applyExport(unique("newer") + ".csv", false);
        String olderName = applyExport(unique("older") + ".csv", false);
        UUID newer = queue(newerName, Instant.now().minusSeconds(10));
        UUID older = queue(olderName, Instant.now().minusSeconds(60));
        Semaphore permits = scheduler.runPermits();
        int held = permits.drainPermits();
        permits.release(1);   // exactly one start is possible
        try {
            assertThat(dispatcher.dispatch()).isEqualTo(1);
            assertThat(settled(older).status()).isEqualTo("Completed");
            assertThat(runRow(newer).status()).as("no permit left for it").isEqualTo("Queued");
        } finally {
            Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> permits.availablePermits() == 1);
            permits.drainPermits();
            permits.release(held);
        }
    }

    @Test
    void a_busy_lock_leaves_the_export_run_queued_for_the_next_poll() throws Exception {
        String name = applyExport(unique("busy") + ".csv", false);
        UUID run = queue(name, Instant.now().minusSeconds(5));

        try (RunLock held = RunLock.tryAcquire(adminDs, exportId(name))) {   // a previous run's worker has not closed it yet
            assertThat(held).isNotNull();
            assertThat(dispatcher.dispatch()).isZero();
            assertThat(runRow(run).status()).as("not a verdict: it stays queued").isEqualTo("Queued");
        }

        assertThat(dispatcher.dispatch()).isEqualTo(1);
        assertThat(settled(run).status()).isEqualTo("Completed");
    }

    /** The run ended without running: Failed/definition, a reason, a start equal to its end, nothing still held. */
    private void assertEndedUnrun(UUID run, String reasonPart, UUID exportId) throws Exception {
        QueuedRun row = runRow(run);
        assertThat(row.status()).isEqualTo("Failed");
        assertThat(row.failureCode()).isEqualTo("definition");
        assertThat(row.failureReason()).contains(reasonPart);
        assertThat(row.startedAt()).as("never left NULL: it would sort above every recent run")
            .isNotNull().isEqualTo(row.endedAt());
        assertThat(lockIsFree(exportId)).as("the lock it took to check was given back").isTrue();
        assertThat(scheduler.runPermits().availablePermits()).isEqualTo(permitsAtRest);
    }

    @Test
    void a_reapplied_export_ends_its_queued_run_failed_definition() throws Exception {
        String name = applyExport(unique("reapplied") + ".csv", false);
        UUID exportId = exportId(name);
        UUID run = queue(name, Instant.now().minusSeconds(5));   // the sha of the definition as accepted
        reapply(name, unique("elsewhere") + ".csv", false);      // a different definition lands while it waits

        assertThat(dispatcher.dispatch()).isZero();

        assertEndedUnrun(run, "definition was re-applied", exportId);
    }

    @Test
    void a_deleted_export_ends_its_queued_run_failed_definition() throws Exception {
        String name = applyExport(unique("deleted") + ".csv", false);
        UUID exportId = exportId(name);
        UUID run = queue(name, Instant.now().minusSeconds(5));
        Jdbc.update(adminDs, "delete the export",
            "DELETE FROM connector.export_manifest WHERE tenant_id = ? AND name = ?",
            ps -> {
                ps.setLong(1, TENANT);
                ps.setString(2, name);
            });

        assertThat(dispatcher.dispatch()).isZero();

        assertEndedUnrun(run, "export was deleted", exportId);
    }

    @Test
    void an_expired_export_run_fails_as_interrupted_and_a_fresh_one_is_left() throws Exception {
        String staleName = applyExport(unique("stale") + ".csv", false);
        String freshName = applyExport(unique("fresh") + ".csv", false);
        UUID stale = queue(staleName, Instant.now().minus(Duration.ofHours(2)));
        UUID fresh = queue(freshName, Instant.now().minusSeconds(5));

        assertThat(dispatcher.sweepExpired(Instant.now(), Duration.ofHours(1))).isEqualTo(1);

        QueuedRun row = runRow(stale);
        assertThat(row.status()).isEqualTo("Failed");
        assertThat(row.failureCode()).isEqualTo("interrupted");
        assertThat(row.failureReason()).isEqualTo("expired in queue");
        assertThat(row.startedAt()).as("never left NULL").isNotNull().isEqualTo(row.endedAt());
        assertThat(runRow(fresh).status()).isEqualTo("Queued");
    }

    /**
     * An attempt that throws (injected through the after-commit hook, the one point a test can make fail) must
     * not abort the poll: the rows behind it still start, and the failed attempt gave back its lock and permit.
     */
    @Test
    void an_attempt_that_throws_does_not_hold_back_the_rows_behind_it() throws Exception {
        String firstName = applyExport(unique("throws") + ".csv", false);
        UUID firstExport = exportId(firstName);
        UUID first = queue(firstName, Instant.now().minusSeconds(60));
        String secondName = applyExport(unique("behind") + ".csv", false);
        UUID second = queue(secondName, Instant.now().minusSeconds(30));
        AtomicBoolean thrown = new AtomicBoolean();
        dispatcher.afterCommit(() -> {
            if (thrown.compareAndSet(false, true)) throw new IllegalStateException("injected");
        });

        assertThat(dispatcher.dispatch()).as("the poll went on past the attempt that threw").isEqualTo(1);

        assertThat(thrown).isTrue();
        assertThat(settled(second).status()).isEqualTo("Completed");
        assertThat(lockIsFree(firstExport)).as("the failed attempt gave its lock back").isTrue();
        // What the throw left behind is a Running row with a free lock: exactly what the reaper collects.
        assertThat(runRow(first).status()).isEqualTo("Running");
        Jdbc.update(adminDs, "drop the orphan", "DELETE FROM connector.export_run WHERE id = ?", ps -> ps.setObject(1, first));
    }

    @Test
    void with_the_flag_off_a_poll_still_drains_what_the_flag_left_queued() throws Exception {
        String stale = applyExport(unique("off1") + ".csv", false);
        String live = applyExport(unique("off2") + ".csv", false);
        UUID expired = queue(stale, Instant.now().minus(Duration.ofHours(3)));   // older than the 1 h max-age: ended, not started
        UUID run = queue(live, Instant.now().minusSeconds(5));

        dispatcher.poll();   // lumnik.run-queue.enabled defaults to false: the queue was switched off with rows in it

        assertThat(runRow(expired).status()).isEqualTo("Failed");
        assertThat(runRow(expired).failureCode()).isEqualTo("interrupted");
        assertThat(settled(run).status()).isEqualTo("Completed");
        // Nothing is stranded: the export whose queued run expired accepts a new run, and it really runs.
        var next = (StartResult.Started) runner.start(ExportRunRequest.manual(TENANT, stale, 7L), row(stale));
        assertThat(settled(next.runId()).status()).isEqualTo("Completed");
    }

    @Test
    void with_the_flag_off_and_nothing_queued_a_poll_touches_nothing() throws Exception {
        String name = applyExport(unique("off3") + ".csv", false);

        assertThat(dispatcher.dispatch()).isZero();
        dispatcher.poll();

        assertThat(runRows(name)).isZero();
    }

    /**
     * The scheduler and a manual start meet a queued run as the run in flight: the new Queued|Running index
     * refuses their claim, and the ledger NAMES the queued run (it named only a Running one), so a manual
     * start still answers "already running" with that run's id — the 409 wording `lm` reads.
     */
    @Test
    void a_start_meets_a_queued_run_as_the_run_in_flight() throws Exception {
        String name = applyExport(unique("meets") + ".csv", false, "",
            "  schedule: \"" + ExportRunSchedulingIT.CRON + "\"");
        UUID queued = queue(name, Instant.now().minusSeconds(5));

        scheduler.tick(ExportRunSchedulingIT.NEW_YEAR);   // other exports may start; this one must not

        assertThat(runRows(name)).as("the refused claim wrote nothing: only the queued run exists").isEqualTo(1);
        assertThat(runRow(queued).status()).isEqualTo("Queued");
        ExportRunner.Launch manual = runner.launch(ExportRunRequest.manual(TENANT, name, 7L));
        assertThat(manual.result()).isEqualTo(new StartResult.AlreadyRunning(queued));
    }

    @Test
    void a_definition_reapplied_after_the_commit_does_not_change_what_runs() throws Exception {
        String accepted = unique("accepted") + ".csv";
        String reappliedTo = unique("reapplied-to") + ".csv";
        String name = applyExport(accepted, false);
        UUID run = queue(name, Instant.now().minusSeconds(5));

        // The window between the commit of Queued → Running and the hand-over: a re-apply lands here.
        AtomicBoolean reapplied = new AtomicBoolean();
        dispatcher.afterCommit(() -> {
            Jdbc.update(adminDs, "re-apply " + name,
                "UPDATE connector.export_manifest SET yaml_text = replace(yaml_text, ?, ?) WHERE tenant_id = ? AND name = ?",
                ps -> {
                    ps.setString(1, accepted);
                    ps.setString(2, reappliedTo);
                    ps.setLong(3, TENANT);
                    ps.setString(4, name);
                });
            reapplied.set(true);
        });

        assertThat(dispatcher.dispatch()).isEqualTo(1);

        assertThat(reapplied).as("the re-apply really landed in the window").isTrue();
        assertThat(settled(run).status()).isEqualTo("Completed");
        assertThat(remote(accepted)).as("the definition that was accepted ran").isNotNull();
        assertThat(remote(reappliedTo)).as("not the one re-applied after the commit").isNull();
    }
}
