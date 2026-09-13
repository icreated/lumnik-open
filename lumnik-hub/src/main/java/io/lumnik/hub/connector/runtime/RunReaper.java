package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.sql.ResultSet;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Fails runs left at {@code Running} by a previous process (audit P13). The scheduler's
 * SELECT_DUE excludes any connector with a Running run, so a hard kill — or the shutdown
 * race where finalizeRun loses to datasource teardown — silenced that connector's schedule
 * forever, with nothing anywhere to clear it. Single-replica by doctrine: at startup, a
 * Running row cannot belong to a live run.
 */
@ApplicationScoped
public class RunReaper {

    private static final Logger LOG = Logger.getLogger(RunReaper.class);

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject IngestionMetrics ingestionMetrics;

    /** A run this reaper failed, and the connector whose schedule it was silencing. */
    public record Reaped(UUID runId, String connector) {}

    /** After Flyway (@Priority(2)) — the run table must exist; before the first scheduler tick
     *  matters not at all (the tick only reads what this repairs). */
    void onStart(@Observes @Priority(6) StartupEvent ev) {
        reapAndReport();
    }

    /**
     * Reaps, then tells the operator WHAT was reaped. A bare count says three schedules were
     * silenced and not which three — and the run id is what every other line a run writes now
     * carries, so the line that ends these runs without them ever knowing carries it too.
     */
    public void reapAndReport() {
        List<Reaped> reaped = reap(Instant.now());
        if (reaped.isEmpty()) return;
        LOG.warnf("Reaped %d run(s) left 'Running' by a previous process — their connectors are schedulable again", reaped.size());
        for (Reaped r : reaped) {
            LOG.warnf("  reaped run %s (connector %s) — its schedule was blocked until now", r.runId(), r.connector());
        }
    }

    /** Count-only form, for callers that just want the repair done. */
    public int reap() {
        return reap(Instant.now()).size();
    }

    /**
     * ONE clock for the two columns of a run's journal. {@code started_at} is written by the JVM;
     * stamping {@code ended_at} with SQL {@code now()} measured the run's span across the JVM's
     * clock and the DATABASE's. On one host the two agree and nothing shows — the day Postgres
     * lives on another machine, a reaped run's span can come out negative.
     */
    public List<Reaped> reap(Instant endedAt) {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "UPDATE connector.connector_run SET status = 'Failed', ended_at = ?, " +
                "error_message = 'reaped at startup: the hub restarted while this run was in flight' " +
                "WHERE status = 'Running' " +
                "RETURNING id, COALESCE(" +
                "  (SELECT c.name FROM connector.connector c WHERE c.id = connector_run.connector_id), " +
                "  connector_id::text)")) {
            ps.setObject(1, endedAt.atOffset(ZoneOffset.UTC));
            List<Reaped> reaped = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    reaped.add(new Reaped(rs.getObject(1, UUID.class), rs.getString(2)));
                    // A reaped run reached a terminal status like any other; the counter is how an
                    // operator tallies them. Bypassing finalizeRun took this with it, so every
                    // crash-killed run was missing from lumnik_runs_total.
                    ingestionMetrics.recordRunFinished("Failed");
                }
            }
            return reaped;
        } catch (SQLException e) {
            // Fail open on the reap itself (a broken reaper must not block boot), but LOUDLY:
            // an unreaped zombie means a silenced schedule.
            LOG.error("RunReaper failed — connectors with a stale Running run stay unscheduled", e);
            return List.of();
        }
    }
}
