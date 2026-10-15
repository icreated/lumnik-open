package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.domain.ConnectorRunStatus;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.run.RunLock;
import io.lumnik.hub.run.RunQueueClaim;
import io.lumnik.hub.run.RunQueueSettings;
import io.lumnik.hub.sql.SqlFailureText;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

/**
 * Starts the connector runs that were accepted into the queue (#952). A queued run belongs to nobody:
 * any replica's dispatcher may start it, so a replica that dies leaves nothing to recover. One start
 * attempt is (D2): a permit from the SAME budget the scheduled fan-out draws on (D11); a transaction that
 * selects one queued row {@code FOR UPDATE SKIP LOCKED}; that row's {@link RunLock}; a check that its
 * endpoint and definition are the ones accepted (D10); {@code Queued → Running}, commit; and the
 * hand-over of lock and permit to the worker. What an attempt leaves behind is D12: a busy lock or a lock
 * error leaves the row queued for the next poll; an endpoint or definition that is gone, or a wait past
 * {@code lumnik.run-queue.max-age}, ends it explicitly: {@code Cancelled}, with the endpoint's {@code last_run_*} stamped in
 * the same transaction, as {@code finalizeRun} does for every ending. With the flag off it only drains rows an earlier flag-on period left.
 */
@ApplicationScoped
public class ConnectorRunDispatcher {

    private static final Logger LOG = Logger.getLogger(ConnectorRunDispatcher.class);

    static final String EXPIRED = "expired in the queue";

    private record Queued(UUID runId, long tenantId, UUID endpointId, String definitionSha256) {}

    private record Verdict(Connector connector, ConnectorEndpoint endpoint, String problem) {
        static Verdict invalid(String problem) { return new Verdict(null, null, problem); }
    }

    // Cross-tenant by design, like RunReaper's: the oldest queued run of ANY tenant. Every write below binds that run's own tenant_id.
    private static final String NEXT =
        "SELECT id, tenant_id, endpoint_id, definition_sha256 FROM connector.connector_run "
      + "WHERE status = 'Queued' AND endpoint_id IS NOT NULL AND NOT (id = ANY (?)) "
      + "ORDER BY queued_at, id LIMIT 1 FOR UPDATE SKIP LOCKED";

    // The start re-reads what the run was queued before: the cursor it resumes from and the fence of its first write.
    private static final String START =
        "UPDATE connector.connector_run SET status = 'Running', started_at = ?, "
      + "cursor_before = (SELECT cursor_value FROM connector.connector_cursor WHERE endpoint_id = ? AND tenant_id = ?), "
      + "resolution_fence_at = clock_timestamp() WHERE id = ? AND tenant_id = ? AND status = 'Queued'";

    private static final String END =
        "UPDATE connector.connector_run SET status = 'Cancelled', started_at = ?, ended_at = ?, error_message = ?, "
      + "resolution_exempt = true WHERE id = ? AND tenant_id = ? AND status = 'Queued'";

    private static final String CANCEL = END + " RETURNING endpoint_id";   // the same ending, asking which endpoint to stamp

    // finalizeRun's invariant: the endpoint shows its last run's ending, stamped in the run's own transaction.
    private static final String STAMP =
        "UPDATE connector.connector_endpoint SET last_run_status = 'Cancelled', last_run_at = ? "
      + "WHERE id = ? AND tenant_id = ?";

    // One statement, so a run and its endpoint end together: `ended` and `stamped` both execute to completion,
    // the final SELECT only reports which runs ended. Cross-tenant by design (age alone); the endpoint join binds tenant_id.
    private static final String EXPIRE =
        "WITH ended AS (UPDATE connector.connector_run SET status = 'Cancelled', started_at = ?, ended_at = ?, "
      + "error_message = ?, resolution_exempt = true WHERE status = 'Queued' AND queued_at < ? "
      + "RETURNING id, tenant_id, endpoint_id), "
      + "stamped AS (UPDATE connector.connector_endpoint e SET last_run_status = 'Cancelled', last_run_at = ? "
      + "FROM ended WHERE e.id = ended.endpoint_id AND e.tenant_id = ended.tenant_id RETURNING e.id) "
      + "SELECT id FROM ended";

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject RunQueueSettings settings;
    @Inject IngestionMetrics metrics;

    /** Between the commit of Queued → Running and the hand-over to the worker: where a re-apply could land. */
    private volatile Runnable afterCommit = () -> {};

    /** Test seam. A method on purpose: this bean is application-scoped, so a field set from outside lands on its proxy. */
    void afterCommit(Runnable hook) {
        this.afterCommit = hook;
    }

    @Scheduled(every = "${lumnik.run-queue.poll-interval:5s}", concurrentExecution = SKIP)
    void poll() {
        // Switched off: the doors queue nothing new, but what an earlier flag-on period accepted is still owed a start or an ending.
        try {
            if (!settings.enabled() && !anyQueued()) return;
            sweepExpired(Instant.now(), settings.maxAge());
            dispatch();
        } catch (RuntimeException e) {
            LOG.error("ConnectorRunDispatcher.poll() failed; will retry", e);
        }
    }

    private boolean anyQueued() {
        return Jdbc.queryOne(adminDs, "ConnectorRunDispatcher any queued",
            "SELECT EXISTS (SELECT 1 FROM connector.connector_run WHERE status = 'Queued')",
            ps -> {}, rs -> rs.getBoolean(1)).orElse(false);
    }

    /** The door that just queued a run calls this: the next poll is not waited for. Several may run at once (SKIP LOCKED, permits). */
    public void wake() {
        if (settings.enabled()) Thread.startVirtualThread(() -> {
            try {
                dispatch();
            } catch (RuntimeException e) {
                LOG.error("ConnectorRunDispatcher wake-up failed; the next poll retries", e);
            }
        });
    }

    /** Starts as many queued runs as there are permits; how many it started. Each row is attempted at most once per call (D2). */
    int dispatch() {
        Semaphore permits = engine.scheduledRunPermits();
        Set<UUID> tried = new HashSet<>();
        int started = 0;
        while (true) {
            if (!acquire(permits)) break;
            boolean handedOver = false;
            try {
                Optional<RunQueueClaim<Queued>> next = RunQueueClaim.next(adminDs, NEXT, tried, rs ->
                    new Queued(rs.getObject(1, UUID.class), rs.getLong(2), rs.getObject(3, UUID.class), rs.getString(4)));
                if (next.isEmpty()) break;
                try (RunQueueClaim<Queued> claim = next.get()) {
                    tried.add(claim.row().runId());
                    try {
                        handedOver = attempt(claim, permits);
                    } catch (SQLException | RuntimeException e) {
                        // One row's failure is that row's: it stays queued (it is in `tried`), the poll goes on.
                        LOG.warnf(e, "queued run %s stays queued: its start attempt failed", claim.row().runId());
                    }
                }
                if (handedOver) started++;
            } catch (SQLException e) {
                LOG.warnf("ConnectorRunDispatcher could not read the queue: %s", e.getMessage());
                break;
            } finally {
                if (!handedOver) permits.release();
            }
        }
        return started;
    }

    /** {@code tryAcquire(0, …)} honours the semaphore's fairness; the no-argument form would barge past waiting fan-outs. */
    private static boolean acquire(Semaphore permits) {
        try {
            return permits.tryAcquire(0, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean attempt(RunQueueClaim<Queued> claim, Semaphore permits) throws SQLException {
        Queued q = claim.row();
        // A lock error propagates: dispatch() logs it, and the row stays queued for the next poll (D12).
        RunLock lock = RunLock.tryAcquire(adminDs, q.endpointId());
        if (lock == null) {
            LOG.debugf("queued run %s stays queued: its endpoint's lock is held", q.runId());
            return false;
        }
        boolean handedOver = false;
        try {
            Verdict verdict = verify(claim);
            if (verdict.problem() != null) {
                end(claim, verdict.problem());
                return false;
            }
            if (!start(claim, Instant.now())) return false;   // no longer Queued: someone else ended it
            claim.commit();
            afterCommit.run();
            engine.startClaimedRun(verdict.connector(), verdict.endpoint(), q.runId(), lock, permits::release);
            handedOver = true;
            return true;
        } finally {
            if (!handedOver) lock.close();
        }
    }

    private Verdict verify(RunQueueClaim<Queued> claim) throws SQLException {
        Queued q = claim.row();
        ConnectorEndpoint endpoint = TenantContext.callAsSystem(() ->
            QuarkusTransaction.requiringNew().call(() -> endpoints.findById(q.endpointId())));
        if (endpoint == null || !endpoint.isEnabled() || endpoint.isDeleted()) {
            return Verdict.invalid("the endpoint was disabled or deleted while it waited");
        }
        Connector c = TenantContext.callAsSystem(() ->
            QuarkusTransaction.requiringNew().call(() -> connectors.findById(endpoint.getConnectorId())));
        if (c == null || !c.isEnabled() || c.isDeleted()) {
            return Verdict.invalid("the connector was paused or deleted while it waited");
        }
        // The reads above are cross-tenant (system scope): the row's tenant must be the target's, or the run
        // would execute one tenant's endpoint and write its ledger under another's.
        if (endpoint.getTenantId() != q.tenantId() || c.getTenantId() != q.tenantId()) {
            return Verdict.invalid("its target belongs to another tenant");
        }
        if (q.definitionSha256() == null) {
            return Verdict.invalid("it was queued without a definition witness");
        }
        String now = DefinitionHash.of(c, endpoint);
        if (!now.equals(q.definitionSha256())) {
            return Verdict.invalid("its definition was re-applied while it waited");
        }
        return new Verdict(c, endpoint, null);
    }

    private boolean start(RunQueueClaim<Queued> claim, Instant startedAt) throws SQLException {
        Queued q = claim.row();
        try (PreparedStatement ps = claim.connection().prepareStatement(START)) {
            ps.setObject(1, startedAt.atOffset(ZoneOffset.UTC));
            ps.setObject(2, q.endpointId());
            ps.setLong(3, q.tenantId());
            ps.setObject(4, q.runId());
            ps.setLong(5, q.tenantId());
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Ends a run that will not start, in the claim's own transaction: the run row and its endpoint's last-run
     * stamp commit together. started_at = ended_at: a zero-length span, never NULL.
     */
    private void end(RunQueueClaim<Queued> claim, String reason) throws SQLException {
        Queued q = claim.row();
        OffsetDateTime at = Instant.now().atOffset(ZoneOffset.UTC);
        try (PreparedStatement run = claim.connection().prepareStatement(END);
             PreparedStatement endpoint = claim.connection().prepareStatement(STAMP)) {
            run.setObject(1, at);
            run.setObject(2, at);
            run.setString(3, reason);
            run.setObject(4, q.runId());
            run.setLong(5, q.tenantId());
            if (run.executeUpdate() == 1) {
                endpoint.setObject(1, at);
                endpoint.setObject(2, q.endpointId());
                endpoint.setLong(3, q.tenantId());
                endpoint.executeUpdate();
            }
        }
        claim.commit();
        metrics.recordRunFinished(ConnectorRunStatus.CANCELLED.sqlValue());
        LOG.warnf("queued run %s ended Cancelled without running: %s", q.runId(), reason);
    }

    /** Ends every run queued for longer than {@code maxAge}; how many (D7). */
    int sweepExpired(Instant now, Duration maxAge) {
        OffsetDateTime at = now.atOffset(ZoneOffset.UTC);
        List<UUID> ended = Jdbc.query(adminDs, "ConnectorRunDispatcher expired queued runs", EXPIRE,
            ps -> {
                ps.setObject(1, at);
                ps.setObject(2, at);
                ps.setString(3, EXPIRED);
                ps.setObject(4, now.minus(maxAge).atOffset(ZoneOffset.UTC));
                ps.setObject(5, at);
            },
            rs -> rs.getObject(1, UUID.class));
        ended.forEach(id -> {
            metrics.recordRunFinished(ConnectorRunStatus.CANCELLED.sqlValue());
            LOG.warnf("queued run %s ended Cancelled: %s (more than %s)", id, EXPIRED, maxAge);
        });
        return ended.size();
    }

    /** What {@link #cancelQueued} did: ended it; found it no longer queued; or could not get its row in time. */
    public enum QueuedCancel { CANCELLED, NOT_QUEUED, BUSY }

    private static final String LOCK_NOT_AVAILABLE = "55P03";

    /**
     * Ends a queued run at its owner's request: the row and its endpoint's last-run stamp commit together.
     * The UPDATE waits up to five seconds while a dispatcher holds the row, then re-checks its status: a run that
     * started in the meantime is {@code NOT_QUEUED} and left to the caller's in-flight cancel; a dispatcher that
     * holds the row for longer is {@code BUSY}, and the run is being started.
     */
    public QueuedCancel cancelQueued(UUID runId, long tenantId) {
        OffsetDateTime at = Instant.now().atOffset(ZoneOffset.UTC);
        try (Connection conn = adminDs.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement run = conn.prepareStatement(CANCEL);
                 PreparedStatement endpoint = conn.prepareStatement(STAMP)) {
                try (Statement st = conn.createStatement()) {
                    st.execute("SET LOCAL lock_timeout = '5s'");
                }
                run.setObject(1, at);
                run.setObject(2, at);
                run.setString(3, "cancelled by user");
                run.setObject(4, runId);
                run.setLong(5, tenantId);
                UUID endpointId;
                try (ResultSet rs = run.executeQuery()) {
                    if (!rs.next()) {
                        rollbackQuietly(conn, null);   // nothing was written; hand the connection back clean
                        return QueuedCancel.NOT_QUEUED;
                    }
                    endpointId = rs.getObject(1, UUID.class);
                }
                if (endpointId != null) {
                    // The five seconds were for the dispatcher's row; the endpoint row is held only briefly by an ending.
                    try (Statement st = conn.createStatement()) {
                        st.execute("SET LOCAL lock_timeout = 0");
                    }
                    endpoint.setObject(1, at);
                    endpoint.setObject(2, endpointId);
                    endpoint.setLong(3, tenantId);
                    endpoint.executeUpdate();
                }
                conn.commit();
                metrics.recordRunFinished(ConnectorRunStatus.CANCELLED.sqlValue());
                return QueuedCancel.CANCELLED;
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(conn, e);
                if (LOCK_NOT_AVAILABLE.equals(SqlFailureText.sqlStateOf(e))) return QueuedCancel.BUSY;
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not cancel queued run " + runId, e);
        }
    }

    /**
     * A failed rollback must not replace the failure that sent us here: it rides along as suppressed. With no such
     * failure (the nothing-to-cancel path) it is the failure, so it is thrown rather than handing a connection in an
     * unknown state back to the pool.
     */
    private static void rollbackQuietly(Connection conn, Exception cause) {
        try {
            conn.rollback();
        } catch (SQLException rollbackFailure) {
            if (cause == null) {
                throw new IllegalStateException("could not roll back", rollbackFailure);
            }
            cause.addSuppressed(rollbackFailure);
        }
    }
}
