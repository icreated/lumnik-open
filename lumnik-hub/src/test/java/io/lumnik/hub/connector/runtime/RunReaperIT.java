package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.domain.ConnectorRunStatus;
import io.lumnik.hub.run.RunLock;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.api.types.SyncMode;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import org.jboss.logmanager.ExtLogRecord;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static io.lumnik.hub.testsupport.EventItSupport.runInTxAsSystem;
import static io.lumnik.hub.connector.domain.ConnectorRunStatus.CANCELLED;
import static io.lumnik.hub.connector.domain.ConnectorRunStatus.COMPLETED;
import static io.lumnik.hub.connector.domain.ConnectorRunStatus.FAILED;
import static io.lumnik.hub.connector.domain.ConnectorRunStatus.RUNNING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Audit P13: a run hard-killed mid-flight stays at {@code Running}, and the scheduler's
 * {@code SELECT_DUE} excludes any connector with a Running run — silencing its schedule
 * forever. The reaper fails such a row, but only when its endpoint's {@link RunLock} is
 * abandoned: with several replicas, a Running row can belong to another pod's live run.
 */
@QuarkusTest
class RunReaperIT {

    private static final long TENANT = 4477L;

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject RunReaper reaper;
    @Inject MeterRegistry registry;
    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject UserTransaction utx;

    private void exec(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private UUID seedConnectorWithRun(String name, String runStatus) {
        UUID connId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        exec("INSERT INTO connector.connector (id, tenant_id, name, type_id, config, "
           + "enabled, tags, sync_mode, skip_policy) VALUES ('" + connId + "', " + TENANT
           + ", '" + name + "', 'jdbc-generic', '{}'::jsonb, true, "
           + "ARRAY['reaper'], 'Incremental', '{\"type\":\"SkipUpTo\",\"maxSkips\":100}'::jsonb)");
        exec("INSERT INTO connector.connector_run (id, tenant_id, connector_id, status, sync_mode, triggered_by) "
           + "VALUES ('" + runId + "', " + TENANT + ", '" + connId + "', '" + runStatus + "', 'Incremental', 'manual')");
        return runId;
    }

    /** A connector with one endpoint and a Running run on it; returns {runId, endpointId}. */
    private UUID[] seedEndpointRun(String name) {
        UUID runId = seedConnectorWithRun(name, RUNNING.sqlValue());
        UUID endpointId = UUID.randomUUID();
        exec("INSERT INTO connector.connector_endpoint (id, connector_id, tenant_id, name, path, config) "
           + "SELECT '" + endpointId + "', connector_id, tenant_id, 'ep', '/', '{}'::jsonb "
           + "FROM connector.connector_run WHERE id = '" + runId + "'");
        exec("UPDATE connector.connector_run SET endpoint_id = '" + endpointId + "' WHERE id = '" + runId + "'");
        return new UUID[]{runId, endpointId};
    }

    private String query(String sql) {
        return Jdbc.queryOne(adminDs, "RunReaperIT query", sql, ps -> {}, rs -> rs.getString(1)).orElseThrow();
    }

    private String[] runRow(UUID runId) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT status, error_message, ended_at::text FROM connector.connector_run WHERE id = '" + runId + "'")) {
            rs.next();
            return new String[]{rs.getString(1), rs.getString(2), rs.getString(3)};
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void a_running_row_left_by_a_dead_process_is_failed_at_startup() {
        UUID zombie = seedConnectorWithRun("zombie_src", RUNNING.sqlValue());

        reaper.reap();

        String[] row = runRow(zombie);
        assertThat(row[0]).as("a Running row nobody holds is a zombie — must be failed").isEqualTo(FAILED.sqlValue());
        assertThat(row[1]).as("the error must say what happened").contains("restarted");
        assertThat(row[2]).as("ended_at must be stamped").isNotNull();
    }

    @Test
    void terminal_rows_are_untouched() {
        UUID done = seedConnectorWithRun("done_src", COMPLETED.sqlValue());

        reaper.reap();

        assertThat(runRow(done)[0]).isEqualTo(COMPLETED.sqlValue());
    }

    /**
     * ONE clock for the two columns of a run's journal. {@code started_at} is written by the JVM
     * ({@code ConnectorRun}'s field default); the reaper stamped {@code ended_at} with SQL
     * {@code now()} — the DATABASE's clock. On one host the two agree and nothing shows; the day
     * Postgres lives on another machine, a reaped run's span is measured across two clocks and can
     * come out negative. Making the instant a PARAMETER is the fix and its own proof: a caller can
     * hand in a value, which {@code now()} made impossible by construction.
     */
    @Test
    void the_reaper_stamps_the_instant_it_was_given_not_the_database_clock() {
        UUID zombie = seedConnectorWithRun("reaper_clock_" + System.nanoTime(), RUNNING.sqlValue());
        Instant chosen = Instant.parse("2019-03-14T15:09:26.535Z");

        reaper.reap(chosen);

        assertThat(endedAt(zombie))
            .as("ended_at must be the instant the caller supplied — the JVM's clock, not the database's")
            .isEqualTo(chosen);
    }

    /**
     * "Reaped 3 run(s)" tells an operator that three schedules were silenced and not WHICH. The
     * run id is what every other line a run writes now carries; the line that ends those runs
     * without them ever knowing must carry it too.
     */
    @Test
    void the_reaper_names_what_it_reaped() {
        UUID zombie = seedConnectorWithRun("reaper_voice_" + System.nanoTime(), RUNNING.sqlValue());

        List<String> lines = capture(() -> reaper.reapAndReport());

        assertThat(lines).as("the startup warning must name the run it failed")
            .anySatisfy(l -> assertThat(l).contains(zombie.toString()));
    }

    /**
     * A reaped run reached a terminal status like any other, and {@code lumnik_runs_total} is how
     * an operator counts them. The reaper once bypassed finalizeRun with bulk SQL and took the
     * counter with it: every crash-killed run was missing from the tally.
     */
    @Test
    void a_reaped_run_is_counted_among_the_runs_that_finished() {
        seedConnectorWithRun("reaper_metric_" + System.nanoTime(), RUNNING.sqlValue());
        double before = failedCount();

        reaper.reap(Instant.now());

        assertThat(failedCount())
            .as("every reaped run counts as a run that finished Failed")
            .isGreaterThan(before);
    }

    @Test
    void a_run_whose_lock_is_held_is_alive_and_left_alone() throws Exception {
        UUID[] ids = seedEndpointRun("reaper_live_" + System.nanoTime());

        try (RunLock live = RunLock.tryAcquire(adminDs, ids[1])) {
            assertThat(live).as("the test must hold the endpoint lock").isNotNull();
            reaper.reap();
            assertThat(runRow(ids[0])[0]).as("another replica's live run must not be reaped").isEqualTo(RUNNING.sqlValue());
        }
    }

    @Test
    void a_run_whose_lock_is_abandoned_fails_like_any_other_failure() {
        UUID[] ids = seedEndpointRun("reaper_dead_" + System.nanoTime());

        reaper.reap();

        assertThat(runRow(ids[0])[0]).isEqualTo(FAILED.sqlValue());
        assertThat(query("SELECT last_run_status FROM connector.connector_endpoint WHERE id = '" + ids[1] + "'"))
            .as("the reap goes through finalizeRun, so the endpoint's last_run_status follows")
            .isEqualTo(FAILED.sqlValue());
        assertThat(query("SELECT count(*) FROM core.event_outbox WHERE event_type = 'connector.run.failed' "
                + "AND tenant_id = " + TENANT + " AND payload->>'run_id' = '" + ids[0] + "'"))
            .as("a reaped run announces its failure like any other")
            .isEqualTo("1");
    }

    @Test
    void a_terminal_run_is_never_moved_by_finalizeRun() {
        UUID done = seedConnectorWithRun("reaper_final_" + System.nanoTime(), COMPLETED.sqlValue());

        boolean moved = engine.finalizeRun(done, FAILED, "late", null, Instant.now());

        assertThat(moved).isFalse();
        assertThat(runRow(done)[0]).isEqualTo(COMPLETED.sqlValue());
    }

    /**
     * The sequential case above only proves the guard reads what is already committed. Here two
     * endings race for one Running row: both calls are parked behind a row lock the test holds, so
     * both enter finalizeRun while the row still reads Running. Releasing the lock lets them run;
     * exactly one may win, its status must stick, and its side effect (the run counter) happens once.
     */
    @Test
    void two_concurrent_endings_have_exactly_one_winner() throws Exception {
        UUID run = seedConnectorWithRun("reaper_race_" + System.nanoTime(), RUNNING.sqlValue());
        double before = finishedCount();

        List<Boolean> results = new CopyOnWriteArrayList<>();
        List<ConnectorRunStatus> winners = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection holder = adminDs.getConnection()) {
            holder.setAutoCommit(false);
            int holderPid;
            try (Statement st = holder.createStatement()) {
                st.execute("SELECT 1 FROM connector.connector_run WHERE id = '" + run + "' FOR UPDATE");
                try (ResultSet rs = st.executeQuery("SELECT pg_backend_pid()")) {
                    rs.next();
                    holderPid = rs.getInt(1);
                }
            }
            List<Future<?>> calls = new ArrayList<>();
            for (ConnectorRunStatus ending : List.of(FAILED, CANCELLED)) {
                calls.add(pool.submit(() -> {
                    boolean won = TenantContext.callAsSystem(() ->
                        engine.finalizeRun(run, ending, "race", null, Instant.now()));
                    results.add(won);
                    if (won) winners.add(ending);
                }));
            }
            awaitLockWaiters(holderPid, 2);
            holder.commit();
            for (Future<?> f : calls) f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(results).hasSize(2);
        assertThat(winners).as("exactly one ending may take effect").hasSize(1);
        assertThat(runRow(run)[0]).as("the winner's status sticks").isEqualTo(winners.get(0).sqlValue());
        assertThat(finishedCount() - before).as("the ending is counted once").isEqualTo(1.0);
    }

    /**
     * Waits until {@code n} backends are queued behind the row lock {@code holderPid} holds. Keyed to
     * the lock itself, not to query text: the first waiter is blocked by the holder, and the second
     * queues behind the first (Postgres hands the tuple lock to the first waiter), so the count is
     * the holder's direct waiters plus theirs.
     */
    private void awaitLockWaiters(int holderPid, int n) throws InterruptedException {
        String sql = "WITH w1 AS (SELECT pid FROM pg_stat_activity WHERE " + holderPid + " = ANY(pg_blocking_pids(pid))) "
                + "SELECT (SELECT count(*) FROM w1) + (SELECT count(*) FROM pg_stat_activity a "
                + "WHERE a.pid NOT IN (SELECT pid FROM w1) AND pg_blocking_pids(a.pid) && ARRAY(SELECT pid FROM w1))";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (Integer.parseInt(query(sql)) >= n) return;
            Thread.sleep(50);
        }
        throw new AssertionError(n + " finalizeRun calls never blocked on the row lock");
    }

    private double finishedCount() {
        return Search.in(registry).name("lumnik_runs_total").counters()
            .stream().filter(c -> List.of(FAILED.sqlValue(), CANCELLED.sqlValue()).contains(c.getId().getTag("status")))
            .mapToDouble(c -> c.count()).sum();
    }

    /**
     * The invariant that lets production reap with no grace (#693): a run the ENGINE starts takes
     * its endpoint lock before its row exists, and hands that lock to the worker that drives it. So
     * a reap with no grace at all, while that run is in flight, must leave it alone.
     *
     * <p>The run is held in flight deterministically: the test takes an ACCESS EXCLUSIVE lock on the
     * run's source table, so the jdbc reader blocks on its first read until the test lets go.
     */
    @Test
    @Timeout(120)
    void a_run_the_engine_started_is_never_reaped_while_in_flight() throws Exception {
        String table = "src_reaper_inflight_" + System.nanoTime();
        UUID connectorId = seedJdbcConnector(table);

        UUID runId;
        try (Connection holder = adminDs.getConnection()) {
            holder.setAutoCommit(false);
            try {
                try (Statement st = holder.createStatement()) {
                    st.execute("LOCK TABLE public." + table + " IN ACCESS EXCLUSIVE MODE");
                }
                List<UUID> ids = engine.submitRun(connectorId, new RunTrigger.Manual(1L));
                assertThat(ids).hasSize(1);
                runId = ids.get(0);
                awaitTableLockWaiter(table);

                assertThat(reaper.reap(Instant.now(), Duration.ZERO))
                    .as("a run whose worker holds its lock is alive, whatever its age")
                    .extracting(RunReaper.Reaped::runId)
                    .doesNotContain(runId);
                assertThat(runRow(runId)[0]).isEqualTo(RUNNING.sqlValue());
            } finally {
                holder.rollback();   // releases the table: the run reads on
                holder.setAutoCommit(true);
            }
        }

        assertThat(awaitTerminal(runId))
            .as("once released, the run ends on its own, not reaped")
            .isEqualTo(COMPLETED.sqlValue());
    }

    private UUID seedJdbcConnector(String table) throws Exception {
        // Not "id": the hub's target table owns that column, and a source column of that name collides.
        exec("CREATE TABLE public." + table + " (source_id bigint primary key, label text)");
        exec("INSERT INTO public." + table + " SELECT g, 'row-' || g FROM generate_series(1, 5) g");
        exec("GRANT SELECT ON public." + table + " TO lumnik_app");
        String url;
        try (Connection c = adminDs.getConnection()) { url = c.getMetaData().getURL(); }
        final String jdbcUrl = url;
        return runInTxAsSystem(utx, () -> {
            Connector c = new Connector();
            c.setTenantId(1L);
            c.setName("reaper-inflight-" + table);
            c.setTypeId("jdbc-generic");
            c.setSyncMode(new SyncMode.FullRefresh());
            c.setConfig(Map.of(
                "url", jdbcUrl, "user", "lumnik_app", "password", "lumnik_app_dev_password",
                "dialect", "postgres", "table-schema", "public", "table-name", table,
                "mode", "full", "pk-columns", List.of("source_id"), "chunk-size", 100));
            connectors.persist(c);
            connectors.flush();
            return c.getId();
        });
    }

    /** Waits until some session is queued behind the test's lock on {@code table}. */
    private void awaitTableLockWaiter(String table) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            if (!"0".equals(query("SELECT count(*) FROM pg_locks WHERE NOT granted "
                    + "AND relation = 'public." + table + "'::regclass"))) return;
            Thread.sleep(50);
        }
        throw new AssertionError("the run never reached its source table");
    }

    private String awaitTerminal(UUID runId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            String status = runRow(runId)[0];
            if (!RUNNING.sqlValue().equals(status)) return status;
            Thread.sleep(100);
        }
        throw new AssertionError("run " + runId + " never ended");
    }

    @Test
    void the_database_refuses_an_unknown_status() {
        assertThatThrownBy(() -> seedConnectorWithRun("reaper_bogus_" + System.nanoTime(), "Done"))
            .hasStackTraceContaining("connector_run_status_check");
    }

    private Instant endedAt(UUID runId) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT ended_at FROM connector.connector_run WHERE id = '" + runId + "'")) {
            rs.next();
            return rs.getObject(1, OffsetDateTime.class).toInstant();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private double failedCount() {
        return Search.in(registry).name("lumnik_runs_total").tag("status", FAILED.sqlValue()).counters()
            .stream().mapToDouble(c -> c.count()).sum();
    }

    /** Runs {@code action} with a probe on the reaper's logger; returns the formatted lines. */
    private static List<String> capture(Runnable action) {
        List<String> lines = new CopyOnWriteArrayList<>();
        Logger log = Logger.getLogger(RunReaper.class.getName());
        Handler probe = new Handler() {
            @Override public void publish(LogRecord r) {
                lines.add(r instanceof ExtLogRecord ext ? ext.getFormattedMessage() : String.valueOf(r.getMessage()));
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        log.addHandler(probe);
        try { action.run(); } finally { log.removeHandler(probe); }
        return lines;
    }
}
