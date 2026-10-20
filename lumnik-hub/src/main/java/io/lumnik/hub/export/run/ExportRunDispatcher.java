package io.lumnik.hub.export.run;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.audit.AuditRecorder;
import io.lumnik.hub.export.ExportManifestStore;
import io.lumnik.hub.export.ExportManifestStore.Row;
import io.lumnik.hub.run.RunLock;
import io.lumnik.hub.run.RunQueueClaim;
import io.lumnik.hub.run.RunQueueSettings;
import io.lumnik.hub.scheduler.TenantScope;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

/**
 * Starts the export runs that were accepted into the queue (#952): the export twin of
 * {@code ConnectorRunDispatcher}, on the same protocol (D2). A queued run belongs to nobody; any replica's
 * dispatcher may start it. One start attempt: a permit from {@link ExportScheduler#runPermits()} (D11); a
 * transaction that selects one queued row {@code FOR UPDATE SKIP LOCKED}; that export's {@link RunLock}; a
 * check that the export still exists and still has the definition that was accepted (D10: the manifest's
 * sha, which {@code export_run} already records); {@code Queued → Running}, commit; and the hand-over of the
 * lock, the permit and the very manifest row that was verified to {@link ExportRunner#startClaimed}. What an
 * attempt leaves behind is D12: a busy lock or a lock error leaves the row queued for the next poll; an
 * export that is gone or re-applied ends it {@code Failed}/{@code definition}; a wait past
 * {@code lumnik.run-queue.max-age} ends it {@code Failed}/{@code interrupted}. With the flag off it only
 * drains rows an earlier flag-on period left.
 */
@ApplicationScoped
public class ExportRunDispatcher {

    private static final Logger LOG = Logger.getLogger(ExportRunDispatcher.class);

    static final String EXPIRED = "expired in queue";

    private record Queued(UUID runId, long tenantId, UUID exportId, String name, ExportTrigger trigger,
                          Long actorId, Instant occurrence, Instant artifactTime, int attempt, UUID retryOf,
                          String manifestSha256) {}

    /** The manifest row to run, or why this run cannot. */
    private record Verdict(Row row, String problem) {
        static Verdict invalid(String problem) { return new Verdict(null, problem); }
    }

    // Cross-tenant by design, like ExportRunReaper's sweep: the oldest queued run of ANY tenant. Every write below binds that run's own tenant_id.
    private static final String NEXT =
        "SELECT id, tenant_id, export_id, export_name, trigger, triggered_by, occurrence, artifact_time, attempt, "
      + "retry_of, manifest_sha256 FROM connector.export_run WHERE status = 'Queued' AND NOT (id = ANY (?)) "
      + "ORDER BY queued_at, id LIMIT 1 FOR UPDATE SKIP LOCKED";

    private static final String START =
        "UPDATE connector.export_run SET status = 'Running', started_at = ? "
      + "WHERE id = ? AND tenant_id = ? AND status = 'Queued'";

    // started_at = ended_at: a run that ends without running gets a zero-length span, never a NULL start.
    private static final String END =
        "UPDATE connector.export_run SET status = 'Failed', started_at = ?, ended_at = ?, failure_code = ?, "
      + "failure_reason = ? WHERE id = ? AND tenant_id = ? AND status = 'Queued'";

    // Cross-tenant by design (age alone), like the connector sweep.
    private static final String EXPIRE =
        "UPDATE connector.export_run SET status = 'Failed', started_at = ?, ended_at = ?, failure_code = ?, "
      + "failure_reason = ? WHERE status = 'Queued' AND queued_at < ? RETURNING id";

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject ExportRunner runner;
    @Inject ExportScheduler scheduler;        // its runPermits() is the export budget (D11)
    @Inject ExportManifestStore store;
    @Inject RunQueueSettings settings;

    /** Between the commit of Queued → Running and the hand-over to the worker: where a re-apply could land. */
    private volatile Runnable afterCommit = () -> {};

    /** Test seam. A method on purpose: this bean is application-scoped, so a field set from outside lands on its proxy. */
    void afterCommit(Runnable hook) {
        this.afterCommit = hook;
    }

    @Scheduled(every = "${lumnik.run-queue.poll-interval:5s}", concurrentExecution = SKIP)
    void poll() {
        try {
            // Switched off: the doors queue nothing new, but what an earlier flag-on period accepted is still owed a start or an ending.
            if (!settings.enabled() && !anyQueued()) return;
            sweepExpired(Instant.now(), settings.maxAge());
            dispatch();
        } catch (RuntimeException e) {
            LOG.error("ExportRunDispatcher.poll() failed; will retry", e);
        }
    }

    private boolean anyQueued() {
        return Jdbc.queryOne(adminDs, "ExportRunDispatcher any queued",
            "SELECT EXISTS (SELECT 1 FROM connector.export_run WHERE status = 'Queued')",
            ps -> {}, rs -> rs.getBoolean(1)).orElse(false);
    }

    /** The door that just queued a run calls this: the next poll is not waited for. Several may run at once (SKIP LOCKED, permits). */
    public void wake() {
        if (settings.enabled()) Thread.startVirtualThread(() -> {
            try {
                dispatch();
            } catch (RuntimeException e) {
                LOG.error("ExportRunDispatcher wake-up failed; the next poll retries", e);
            }
        });
    }

    /** Starts as many queued runs as there are permits; how many it started. Each row is attempted at most once per call (D2). */
    int dispatch() {
        Semaphore permits = scheduler.runPermits();
        Set<UUID> tried = new HashSet<>();
        int started = 0;
        while (permits.tryAcquire()) {   // plain tryAcquire: the export scheduler never waits on this semaphore, so there is no turn to honour (spec D11)
            boolean handedOver = false;
            try {
                Optional<RunQueueClaim<Queued>> next =
                    RunQueueClaim.next(adminDs, NEXT, tried, ExportRunDispatcher::queued);
                if (next.isEmpty()) break;
                try (RunQueueClaim<Queued> claim = next.get()) {
                    tried.add(claim.row().runId());
                    try {
                        handedOver = attempt(claim, permits);
                    } catch (SQLException | RuntimeException e) {
                        // One row's failure is that row's: it stays queued (it is in `tried`), the poll goes on.
                        LOG.warnf(e, "queued export run %s stays queued: its start attempt failed", claim.row().runId());
                    }
                }
                if (handedOver) started++;
            } catch (SQLException e) {
                LOG.warnf("ExportRunDispatcher could not read the queue: %s", e.getMessage());
                break;
            } finally {
                if (!handedOver) permits.release();   // the worker hands the permit back itself once it has
            }
        }
        return started;
    }

    private static Queued queued(ResultSet rs) throws SQLException {
        return new Queued(rs.getObject(1, UUID.class), rs.getLong(2), rs.getObject(3, UUID.class), rs.getString(4),
            ExportTrigger.fromSql(rs.getString(5)), (Long) rs.getObject(6), Jdbc.instant(rs, 7), Jdbc.instant(rs, 8),
            rs.getInt(9), rs.getObject(10, UUID.class), rs.getString(11));
    }

    private boolean attempt(RunQueueClaim<Queued> claim, Semaphore permits) throws SQLException {
        Queued q = claim.row();
        // A lock error propagates: dispatch() logs it, and the row stays queued for the next poll (D12).
        RunLock lock = RunLock.tryAcquire(adminDs, q.exportId());
        if (lock == null) {
            LOG.debugf("queued export run %s stays queued: the export's lock is held", q.runId());
            return false;
        }
        boolean handedOver = false;
        try {
            Verdict verdict = verify(q);
            if (verdict.problem() != null) {
                end(claim, verdict.problem());
                return false;
            }
            if (!start(claim, Instant.now())) return false;   // no longer Queued: someone else ended it
            claim.commit();
            afterCommit.run();
            ExportRunRequest request = new ExportRunRequest(q.tenantId(), q.name(), q.trigger(), q.actorId(),
                q.occurrence(), q.artifactTime(), q.attempt(), q.retryOf());
            CompletableFuture<?> worker = runner.startClaimed(q.runId(), request, verdict.row(), q.artifactTime(), lock);
            handedOver = true;
            worker.whenComplete((ignored, failure) -> permits.release());   // after the worker has released the lock
            return true;
        } finally {
            if (!handedOver) lock.close();
        }
    }

    /** The export is still there under the id it was accepted for, and its definition is the one that was accepted (D10). */
    private Verdict verify(Queued q) {
        Optional<Row> found = TenantScope.get(q.tenantId(), () ->
            QuarkusTransaction.requiringNew().call(() -> store.find(q.tenantId(), q.name())));
        if (found.isEmpty() || !found.get().id().equals(q.exportId())) {
            return Verdict.invalid("the export was deleted while it waited");
        }
        if (!AuditRecorder.sha256(found.get().yaml()).equals(q.manifestSha256())) {
            return Verdict.invalid("its definition was re-applied while it waited");
        }
        return new Verdict(found.get(), null);
    }

    private boolean start(RunQueueClaim<Queued> claim, Instant startedAt) throws SQLException {
        Queued q = claim.row();
        try (PreparedStatement ps = claim.connection().prepareStatement(START)) {
            ps.setObject(1, startedAt.atOffset(ZoneOffset.UTC));
            ps.setObject(2, q.runId());
            ps.setLong(3, q.tenantId());
            return ps.executeUpdate() == 1;
        }
    }

    /** Ends a run that will not start, in the claim's own transaction. An export carries no endpoint-style summary to stamp. */
    private void end(RunQueueClaim<Queued> claim, String reason) throws SQLException {
        Queued q = claim.row();
        OffsetDateTime at = Instant.now().atOffset(ZoneOffset.UTC);
        try (PreparedStatement ps = claim.connection().prepareStatement(END)) {
            ps.setObject(1, at);
            ps.setObject(2, at);
            ps.setString(3, ExportFailureCode.DEFINITION.sqlValue());
            ps.setString(4, reason);
            ps.setObject(5, q.runId());
            ps.setLong(6, q.tenantId());
            ps.executeUpdate();
        }
        claim.commit();
        LOG.warnf("queued export run %s ended Failed/definition without running: %s", q.runId(), reason);
    }

    /** Ends every run queued for longer than {@code maxAge}; how many (D7). */
    int sweepExpired(Instant now, Duration maxAge) {
        OffsetDateTime at = now.atOffset(ZoneOffset.UTC);
        List<UUID> ended = Jdbc.query(adminDs, "ExportRunDispatcher expired queued runs", EXPIRE,
            ps -> {
                ps.setObject(1, at);
                ps.setObject(2, at);
                ps.setString(3, ExportFailureCode.INTERRUPTED.sqlValue());
                ps.setString(4, EXPIRED);
                ps.setObject(5, now.minus(maxAge).atOffset(ZoneOffset.UTC));
            },
            rs -> rs.getObject(1, UUID.class));
        ended.forEach(id -> LOG.warnf("queued export run %s ended Failed/interrupted: %s (more than %s)", id, EXPIRED, maxAge));
        return ended.size();
    }
}
