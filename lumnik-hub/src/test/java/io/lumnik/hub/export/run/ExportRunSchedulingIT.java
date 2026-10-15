package io.lumnik.hub.export.run;

import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.run.RunLock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExportScheduler} and {@link ExportRunReaper} over the SFTP destination.
 *
 * <p>The real {@code @Scheduled} tick is live during these tests, so every schedule here is
 * {@code 0 4 1 1 *} (04:00 on 1 January): on the real clock its latest slot is before the export
 * was applied, so the live tick never finds it due. The tests drive {@code tick} at a fixed
 * {@link #NEW_YEAR} instead, whose slot is after {@code created_at}.
 */
@QuarkusTest
class ExportRunSchedulingIT extends ExportRunITSupport {

    static final String CRON = "0 4 1 1 *";
    static final Instant SLOT = Instant.parse("2099-01-01T04:00:00Z");
    static final Instant NEW_YEAR = SLOT.plusSeconds(90);

    @Inject ExportScheduler scheduler;
    @Inject ExportRunReaper reaper;

    @BeforeAll
    static void startSftp() throws Exception {
        ExportRunSftpIT.startSftp();
    }

    @AfterAll
    static void stopSftp() {
        ExportRunSftpIT.stopSftp();
    }

    @Override
    String entity() {
        return "ExportRunSchedEntity680";
    }

    @Override
    String destination(String target, boolean wrongCredential) {
        return ExportRunSftpIT.sftpDestination(target, wrongCredential);
    }

    @Override
    byte[] remote(String target) throws Exception {
        return ExportRunSftpIT.sftpRemote(target);
    }

    @Override
    String location(String target) {
        return "/out/" + target;
    }

    @Override
    void assertNoLeftover(String target) {}

    private String applyScheduled(String target, boolean wrongCredential) {
        return applyExport(target, wrongCredential, "", "  schedule: \"" + CRON + "\"");
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** This export's runs as (status, trigger, occurrence, artifact_time), awaited out of Running. */
    private List<String[]> settledRuns(String name) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (true) {
            List<String[]> rows = Jdbc.query(adminDs, "settledRuns " + name,
                "SELECT status, trigger, occurrence, artifact_time, triggered_by, attempt "
              + "FROM connector.export_run WHERE export_name = ?",
                ps -> ps.setString(1, name),
                rs -> new String[] {rs.getString(1), rs.getString(2),
                    instant(rs, 3), instant(rs, 4), rs.getString(5), rs.getString(6)});
            if (rows.stream().noneMatch(r -> r[0].equals(ExportRunStatus.RUNNING.sqlValue())))
                return rows;
            if (System.nanoTime() > deadline) throw new AssertionError("run still Running: " + name);
            Thread.sleep(100);
        }
    }

    private static String instant(ResultSet rs, int i) throws Exception {
        OffsetDateTime t = rs.getObject(i, OffsetDateTime.class);
        return t == null ? null : t.toInstant().toString();
    }

    @Test
    void aDueTickStartsOneScheduledRunThroughTheRunner() throws Exception {
        String prefix = unique("sched");
        String name = applyScheduled(prefix + "-${date}.csv", false);

        scheduler.tick(NEW_YEAR);

        List<String[]> runs = settledRuns(name);
        assertThat(runs).hasSize(1);
        String[] run = runs.getFirst();
        assertThat(run[0]).isEqualTo(ExportRunStatus.COMPLETED.sqlValue());
        assertThat(run[1]).isEqualTo(ExportTrigger.SCHEDULE.sqlValue());
        assertThat(run[2]).isEqualTo(SLOT.toString());
        assertThat(run[3]).isEqualTo(SLOT.toString());
        assertThat(run[4]).isNull();
        assertThat(run[5]).isEqualTo("1");
        // The name resolved against the occurrence, not the wall clock.
        assertThat(remote(prefix + "-2099-01-01.csv")).isNotNull();
    }

    @Test
    void twoConcurrentTicksClaimTheOccurrenceOnce() throws Exception {
        String name = applyScheduled(unique("race") + "-${date}.csv", false);

        CountDownLatch go = new CountDownLatch(1);
        Callable<Integer> tick = () -> {
            go.await();
            return scheduler.tick(NEW_YEAR);
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(tick);
            Future<Integer> b = pool.submit(tick);
            go.countDown();
            a.get();
            b.get();
        } finally {
            pool.shutdownNow();
        }

        assertThat(settledRuns(name)).hasSize(1);
    }

    @Test
    void aFailedOccurrenceIsNotRetriedByTheNextTick() throws Exception {
        String name = applyScheduled(unique("failing") + ".csv", true);

        scheduler.tick(NEW_YEAR);
        List<String[]> first = settledRuns(name);
        assertThat(first).hasSize(1);
        assertThat(first.getFirst()[0]).isEqualTo(ExportRunStatus.FAILED.sqlValue());

        scheduler.tick(NEW_YEAR.plusSeconds(60));

        assertThat(settledRuns(name)).hasSize(1);
    }

    /**
     * #897: a due slot that finds every scheduled-run permit taken starts nothing and writes nothing,
     * so it is still due on the next tick, which delivers that same occurrence.
     */
    @Test
    void aDueSlotWithNoFreeRunPermitStaysDueForTheNextTick() throws Exception {
        String prefix = unique("bounded");
        String name = applyScheduled(prefix + "-${date}.csv", false);
        Semaphore permits = scheduler.runPermits();
        int held = permits.drainPermits();
        try {
            scheduler.tick(NEW_YEAR);
            assertThat(runRows(name)).as("no permit, no row: the occurrence stays unclaimed").isZero();
        } finally {
            permits.release(held);
        }

        scheduler.tick(NEW_YEAR.plusSeconds(60));

        List<String[]> runs = settledRuns(name);
        assertThat(runs).hasSize(1);
        assertThat(runs.getFirst()[0]).isEqualTo(ExportRunStatus.COMPLETED.sqlValue());
        assertThat(runs.getFirst()[2]).as("the same occurrence, not a later one").isEqualTo(SLOT.toString());
        assertThat(remote(prefix + "-2099-01-01.csv")).isNotNull();
        // The worker hands its permit back right after the terminal write settledRuns waited for.
        // At least: the live tick may have had a run of its own in flight when the permits were drained.
        // Awaitility named in full: ExportRunITSupport has an await(name, launch) of its own.
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertThat(permits.availablePermits()).as("every permit came back").isGreaterThanOrEqualTo(held));
    }

    @Test
    void aSlotBeforeTheExportExistedStartsNothing() throws Exception {
        String name = applyScheduled(unique("late") + ".csv", false);

        // On the real clock the latest 1-January slot predates this export's created_at.
        scheduler.tick(Instant.now());

        assertThat(runRows(name)).isZero();
    }

    @Test
    void theReaperFailsAnAbandonedRunAndLeavesALockedOne() throws Exception {
        String abandoned = applyExport(unique("abandoned") + ".csv", false);
        String alive = applyExport(unique("alive") + ".csv", false);
        // Older than the test profile's tick grace, so the tick form looks at both.
        Instant old = Instant.now().minus(Duration.ofMinutes(10));
        UUID abandonedRun = insertRunning(abandoned, old);
        UUID aliveRun = insertRunning(alive, old);

        try (RunLock held = RunLock.tryAcquire(adminDs, exportId(alive))) {
            assertThat(held).isNotNull();
            reaper.reapOnTick();

            assertThat(status(abandonedRun)).containsExactly(
                ExportRunStatus.FAILED.sqlValue(), ExportFailureCode.INTERRUPTED.sqlValue());
            assertThat(status(aliveRun)).containsExactly(ExportRunStatus.RUNNING.sqlValue(), null);
        }
        reaper.reapOnTick();
        assertThat(status(aliveRun)).containsExactly(
            ExportRunStatus.FAILED.sqlValue(), ExportFailureCode.INTERRUPTED.sqlValue());
    }

    /** A bare Running row, as a hub that died mid-run leaves it: no lock held by anyone. */
    private UUID insertRunning(String name, Instant startedAt) {
        UUID exportId = exportId(name);
        return Jdbc.queryOne(adminDs, "insertRunning " + name,
            "INSERT INTO connector.export_run (tenant_id, export_id, export_name, scope, "
          + "entity_name, destination_kind, manifest_sha256, trigger, artifact_time, status, "
          + "started_at) VALUES (?, ?, ?, 'sales', ?, 'sftp', 'x', 'manual', ?, 'Running', ?) "
          + "RETURNING id",
            ps -> {
                ps.setLong(1, TENANT);
                ps.setObject(2, exportId);
                ps.setString(3, name);
                ps.setString(4, entity());
                ps.setObject(5, startedAt.atOffset(ZoneOffset.UTC));
                ps.setObject(6, startedAt.atOffset(ZoneOffset.UTC));
            },
            rs -> rs.getObject(1, UUID.class)).orElseThrow();
    }

    private String[] status(UUID runId) {
        return Jdbc.queryOne(adminDs, "status " + runId,
            "SELECT status, failure_code FROM connector.export_run WHERE id = ?",
            ps -> ps.setObject(1, runId),
            rs -> new String[] {rs.getString(1), rs.getString(2)}).orElseThrow();
    }
}
