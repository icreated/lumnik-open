package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.connector.domain.ConnectorRunStatus;
import io.lumnik.hub.run.RunLock;
import io.quarkus.agroal.DataSource;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Fails runs left at {@code Running} by a process that is gone (audit P13). The scheduler's
 * SELECT_DUE excludes any connector with a Running run, so a hard kill — or the shutdown race
 * where finalizeRun loses to datasource teardown — would silence that connector's schedule forever.
 *
 * <p>A row is reaped only when its endpoint's {@link RunLock} is ABANDONED. A live run holds that
 * lock for its whole life, on any replica, and Postgres drops it when the owning session dies. So
 * "Running and nobody holds the lock" means a dead owner, and "Running and locked" means a live run
 * that is left alone. The engine takes the lock before it writes the row, so a live run is never seen
 * unlocked. A row written by someone else before its lock (a test, a tool) is covered too: the reaper
 * keeps the lock while it fails the row, and the engine re-checks the row once it holds the lock, so
 * that run cannot be reaped and then run anyway.
 *
 * <p>Every reap goes through {@link ConnectorEngine#reapRun}, the same ending as any failure: the
 * {@code connector.run.failed} event, the {@code lumnik_runs_total} counter and the endpoint's
 * {@code last_run_status}. Runs at startup and on every {@link ConnectorScheduler} tick.
 */
@ApplicationScoped
public class RunReaper {

    private static final Logger LOG = Logger.getLogger(RunReaper.class);

    static final String REASON =
        "reaped: the hub process running it restarted or died while it was in flight";

    private static final String SELECT_RUNNING =
        "SELECT r.id, r.tenant_id, r.endpoint_id, COALESCE(c.name, r.connector_id::text) " +
        "FROM connector.connector_run r " +
        "LEFT JOIN connector.connector c ON c.id = r.connector_id " +
        "WHERE r.status = '" + ConnectorRunStatus.RUNNING.sqlValue() + "'";

    /**
     * How old a Running row must be before a scheduler tick looks at it. Zero in production: the
     * engine takes a run's lock BEFORE it commits the row, so a Running row with a free lock is
     * abandoned the moment it is seen. Only writers that skip the engine — ITs that insert a Running
     * row and drive the chunk loop directly, without the lock — need the sweep kept off their rows;
     * the test profile sets this for them.
     */
    @ConfigProperty(name = "lumnik.run-reaper.tick-grace", defaultValue = "0s")
    Duration tickGrace;

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject ConnectorEngine engine;

    /** A run this reaper failed, and the connector whose schedule it was silencing. */
    public record Reaped(UUID runId, String connector) {}

    private record Candidate(UUID runId, long tenantId, UUID endpointId, String connector) {}

    /** After Flyway (@Priority(2)) — the run table must exist. */
    void onStart(@Observes @Priority(6) StartupEvent ev) {
        reapAndReport();
    }

    /** The scheduler tick's form: rows older than the configured grace. */
    public void reapOnTick() {
        reapAndReport(tickGrace);
    }

    /** Every Running row, whatever its age: the startup form. */
    public void reapAndReport() {
        reapAndReport(Duration.ZERO);
    }

    /**
     * Reaps the Running rows at least {@code minAge} old, then tells the operator WHAT was reaped.
     * A bare count says three schedules were silenced and not which three — and the run id is what
     * every other line a run writes now carries, so the line that ends these runs without them ever
     * knowing carries it too.
     */
    void reapAndReport(Duration minAge) {
        List<Reaped> reaped = reap(Instant.now(), minAge);
        if (reaped.isEmpty()) return;
        LOG.warnf("Reaped %d run(s) left 'Running' by a process that is gone — their connectors are schedulable again", reaped.size());
        for (Reaped r : reaped) {
            LOG.warnf("  reaped run %s (connector %s) — its schedule was blocked until now", r.runId(), r.connector());
        }
    }

    /** Count-only form, for callers that just want the repair done. */
    public int reap() {
        return reap(Instant.now()).size();
    }

    /**
     * ONE clock for the two columns of a run's journal. {@code started_at} is written by the JVM,
     * and so is {@code ended_at}: the caller's instant, never SQL {@code now()}, so a reaped run's
     * span is never measured across two clocks.
     */
    public List<Reaped> reap(Instant endedAt) {
        return reap(endedAt, Duration.ZERO);
    }

    List<Reaped> reap(Instant endedAt, Duration minAge) {
        List<Reaped> reaped = new ArrayList<>();
        // No grace means every Running row, whichever clock stamped its started_at: no age filter at
        // all, so a row from a node whose clock ran fast is never skipped.
        Instant startedBy = minAge.isZero() ? null : Instant.now().minus(minAge);
        for (Candidate c : running(startedBy)) {
            try {
                if (reapIfAbandoned(c, endedAt)) reaped.add(new Reaped(c.runId(), c.connector()));
            } catch (Exception e) {
                // One bad row must not stop the others; the next tick retries it.
                LOG.errorf(e, "RunReaper could not check run %s — it stays Running until the next tick", c.runId());
            }
        }
        return reaped;
    }

    private boolean reapIfAbandoned(Candidate c, Instant endedAt) throws SQLException {
        if (c.endpointId() == null) {
            // A run from before per-endpoint runs (V17) has no lock to hold and no live owner.
            return engine.reapRun(c.runId(), c.tenantId(), REASON, endedAt);
        }
        return RunLock.runIfAbandoned(adminDs, c.endpointId(), () -> engine.reapRun(c.runId(), c.tenantId(), REASON, endedAt));
    }

    /** Every Running row, or only those started at or before {@code startedBy} when it is set. */
    private List<Candidate> running(Instant startedBy) {
        String sql = startedBy == null ? SELECT_RUNNING : SELECT_RUNNING + " AND r.started_at <= ?";
        try {
            return Jdbc.query(adminDs, "RunReaper running runs", sql,
                    ps -> { if (startedBy != null) ps.setObject(1, startedBy.atOffset(ZoneOffset.UTC)); },
                    rs -> new Candidate(rs.getObject(1, UUID.class), rs.getLong(2),
                            rs.getObject(3, UUID.class), rs.getString(4)));
        } catch (RuntimeException e) {
            // Fail open (a broken reaper must not block boot or the scheduler), but LOUDLY:
            // an unreaped zombie means a silenced schedule.
            LOG.error("RunReaper failed — connectors with a stale Running run stay unscheduled", e);
            return List.of();
        }
    }
}
