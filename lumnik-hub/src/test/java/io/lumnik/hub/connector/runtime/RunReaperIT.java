package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import org.jboss.logmanager.ExtLogRecord;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit P13: a run hard-killed mid-flight stays at {@code Running}, and the scheduler's
 * {@code SELECT_DUE} excludes any connector with a Running run — silencing its schedule
 * forever, with no reaper anywhere. Single-replica by doctrine, a Running row at startup
 * cannot belong to a live run: the reaper fails it, restoring the schedule.
 */
@QuarkusTest
class RunReaperIT {

    private static final long TENANT = 4477L;

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject RunReaper reaper;
    @Inject MeterRegistry registry;

    private void exec(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private UUID seedConnectorWithRun(String name, String runStatus) {
        UUID mappingId = UUID.randomUUID();
        UUID connId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        exec("INSERT INTO connector.connector_mapping (id, tenant_id, name, target_entity, spec_format, spec_content) "
           + "VALUES ('" + mappingId + "', " + TENANT + ", 'm_" + name + "', 'public." + name + "', 'yaml', 'x') "
           + "");
        exec("INSERT INTO connector.connector (id, tenant_id, name, type_id, config, mapping_id, "
           + "enabled, tags, sync_mode, skip_policy) VALUES ('" + connId + "', " + TENANT
           + ", '" + name + "', 'jdbc-generic', '{}'::jsonb, '" + mappingId + "', true, "
           + "ARRAY['reaper'], 'Incremental', '{\"type\":\"SkipUpTo\",\"maxSkips\":100}'::jsonb)");
        exec("INSERT INTO connector.connector_run (id, tenant_id, connector_id, status, sync_mode, triggered_by) "
           + "VALUES ('" + runId + "', " + TENANT + ", '" + connId + "', '" + runStatus + "', 'Incremental', 'manual')");
        return runId;
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
        UUID zombie = seedConnectorWithRun("zombie_src", "Running");

        reaper.reap();

        String[] row = runRow(zombie);
        assertThat(row[0]).as("a startup Running row is a zombie — must be failed").isEqualTo("Failed");
        assertThat(row[1]).as("the error must say what happened").contains("restarted");
        assertThat(row[2]).as("ended_at must be stamped").isNotNull();
    }

    @Test
    void terminal_rows_are_untouched() {
        UUID done = seedConnectorWithRun("done_src", "Completed");

        reaper.reap();

        assertThat(runRow(done)[0]).isEqualTo("Completed");
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
        UUID zombie = seedConnectorWithRun("reaper_clock_" + System.nanoTime(), "Running");
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
        UUID zombie = seedConnectorWithRun("reaper_voice_" + System.nanoTime(), "Running");

        List<String> lines = capture(() -> reaper.reapAndReport());

        assertThat(lines).as("the startup warning must name the run it failed")
            .anySatisfy(l -> assertThat(l).contains(zombie.toString()));
    }

    /**
     * A reaped run reached a terminal status like any other, and {@code lumnik_runs_total} is how
     * an operator counts them. The reaper bypasses finalizeRun by design (bulk SQL), and took the
     * counter with it: every crash-killed run was missing from the tally.
     */
    @Test
    void a_reaped_run_is_counted_among_the_runs_that_finished() {
        seedConnectorWithRun("reaper_metric_" + System.nanoTime(), "Running");
        double before = failedCount();

        reaper.reap(Instant.now());

        assertThat(failedCount())
            .as("every reaped run counts as a run that finished Failed")
            .isGreaterThan(before);
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
        return Search.in(registry).name("lumnik_runs_total").tag("status", "Failed").counters()
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
