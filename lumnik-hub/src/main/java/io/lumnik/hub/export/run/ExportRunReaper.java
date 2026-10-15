package io.lumnik.hub.export.run;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.run.RunLock;
import io.lumnik.hub.scheduler.TenantScope;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
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
 * Fails export runs left {@code Running} by a process that is gone, the {@code RunReaper} rule
 * applied to {@code connector.export_run}. A crashed run would otherwise hold
 * {@code uq_export_run_in_flight} forever, refusing every later manual or scheduled run.
 *
 * <p>A row is reaped only when its export's {@link RunLock} is ABANDONED. {@link ExportRunner}
 * takes that lock before it claims the row and releases it only after the terminal write, and
 * Postgres drops it when the owning session dies — so "Running and nobody holds the lock" means a
 * dead owner, and "Running and locked" a live run on some replica, left alone. The reaper keeps
 * the lock while it fails the row, and the ledger's {@code WHERE status='Running'} guard means a
 * run that finished meanwhile is never overwritten.
 *
 * <p>The failure is {@code interrupted}: the hub cannot know whether the artifact was published,
 * so the ledger under-reports rather than over-reports. Runs at startup and on every
 * {@link ExportScheduler} tick.
 */
@ApplicationScoped
public class ExportRunReaper {

    private static final Logger LOG = Logger.getLogger(ExportRunReaper.class);

    static final String REASON =
        "reaped: the hub lost this run; the artifact may or may not have been published";

    /** A status sweep, cross-tenant on the admin datasource: definitions only, never data. */
    private static final String SELECT_RUNNING =
        "SELECT id, tenant_id, export_id, export_name FROM connector.export_run " +
        "WHERE status = '" + ExportRunStatus.RUNNING.sqlValue() + "'";

    /** The same knob as {@code RunReaper}'s: zero in production, set by the test profile. */
    @ConfigProperty(name = "lumnik.run-reaper.tick-grace", defaultValue = "0s")
    Duration tickGrace;

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject ExportRunLedger ledger;

    /** A run this reaper failed, and the export whose runs it was blocking. */
    public record Reaped(UUID runId, String export) {}

    private record Candidate(UUID runId, long tenantId, UUID exportId, String export) {}

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

    /** Reaps the Running rows at least {@code minAge} old, then names each one it failed. */
    void reapAndReport(Duration minAge) {
        List<Reaped> reaped = reap(Instant.now(), minAge);
        if (reaped.isEmpty()) return;
        LOG.warnf("Reaped %d export run(s) left 'Running' by a process that is gone — their exports can run again", reaped.size());
        for (Reaped r : reaped) {
            LOG.warnf("  reaped export run %s (export %s) — failed as interrupted", r.runId(), r.export());
        }
    }

    List<Reaped> reap(Instant endedAt, Duration minAge) {
        List<Reaped> reaped = new ArrayList<>();
        // No grace means every Running row, whichever clock stamped its started_at.
        Instant startedBy = minAge.isZero() ? null : Instant.now().minus(minAge);
        for (Candidate c : running(startedBy)) {
            try {
                if (reapIfAbandoned(c, endedAt)) reaped.add(new Reaped(c.runId(), c.export()));
            } catch (Exception e) {
                // One bad row must not stop the others; the next tick retries it.
                LOG.errorf(e, "ExportRunReaper could not check run %s — it stays Running until the next tick", c.runId());
            }
        }
        return reaped;
    }

    private boolean reapIfAbandoned(Candidate c, Instant endedAt) throws SQLException {
        return RunLock.runIfAbandoned(adminDs, c.exportId(), () -> TenantScope.get(c.tenantId(), () ->
            QuarkusTransaction.requiringNew().call(() -> ledger.fail(c.runId(), endedAt, ExportFailureCode.INTERRUPTED, REASON))));
    }

    /** Every Running row, or only those started at or before {@code startedBy} when it is set. */
    private List<Candidate> running(Instant startedBy) {
        String sql = startedBy == null ? SELECT_RUNNING : SELECT_RUNNING + " AND started_at <= ?";
        try {
            return Jdbc.query(adminDs, "ExportRunReaper running runs", sql,
                    ps -> { if (startedBy != null) ps.setObject(1, startedBy.atOffset(ZoneOffset.UTC)); },
                    rs -> new Candidate(rs.getObject(1, UUID.class), rs.getLong(2),
                            rs.getObject(3, UUID.class), rs.getString(4)));
        } catch (RuntimeException e) {
            // Fail open (a broken reaper must not block boot or the scheduler), but LOUDLY:
            // an unreaped zombie refuses every later run of its export.
            LOG.error("ExportRunReaper failed — exports with a stale Running run cannot run again", e);
            return List.of();
        }
    }
}
