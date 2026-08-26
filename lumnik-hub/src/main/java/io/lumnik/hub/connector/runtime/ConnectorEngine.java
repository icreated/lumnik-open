package io.lumnik.hub.connector.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.ConnectorType;
import io.lumnik.hub.connector.api.types.*;
import io.lumnik.hub.tenant.TenantContextFilter;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorCursorEntity;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.connector.event.ConnectorEvents;
import io.lumnik.hub.connector.event.ConnectorRunCompleted;
import io.lumnik.hub.connector.event.RunTableStats;
import io.lumnik.hub.connector.registry.ConnectorTypeRegistry;
import io.lumnik.hub.connector.repository.ConnectorCursorEntityRepository;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@ApplicationScoped
public class ConnectorEngine {

    private static final String STATUS_FAILED = "Failed";
    private static final String RUN_ID = "run_id";

    private static final Logger LOG = Logger.getLogger(ConnectorEngine.class);

    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject EndpointResolver endpointResolver;
    @Inject ConnectorRunRepository runs;
    @Inject ConnectorCursorEntityRepository cursors;
    @Inject ChunkExecutor chunkExecutor;
    @Inject ConnectorTypeRegistry registry;
    @Inject ConnectorEvents events;
    @Inject RunTableStats stats;
    @Inject Event<ConnectorRunCompleted> runCompleted;
    @Inject ObjectMapper mapper;
    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject IngestionMetrics ingestionMetrics;

    private ExecutorService vt;
    private final Map<UUID, Boolean> cancelFlags = new ConcurrentHashMap<>();

    void onStart(@Observes StartupEvent ev) {
        vt = Executors.newVirtualThreadPerTaskExecutor();
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (vt != null) vt.shutdownNow();
    }

    /**
     * Fan-out trigger that returns REAL, dereferenceable run identities. Resolves the connector's
     * enabled endpoints and, for EACH endpoint, persists its {@code connector_run} row (status
     * {@code Running}) <em>synchronously</em> on the calling thread before returning. The returned
     * list is the run UUIDs in endpoint-resolution order; for a single-endpoint (flat) connector
     * that is a one-element list. Each endpoint's chunk loop then runs asynchronously on the vthread
     * executor and finalizes THAT SAME row (no second row is ever created).
     *
     * <p>The async worker can never swallow a failure: any thrown exception finalizes the run row as
     * {@code Failed} (with message + stack) and is logged at ERROR, so the outcome is always
     * observable on the run row.
     *
     * @return the persisted run ids, one per enabled endpoint (empty if the connector has none).
     */
    public List<UUID> submitRun(UUID connectorId, RunTrigger trigger) {
        return submitRunHandles(connectorId, trigger).stream().map(RunHandle::runId).toList();
    }

    /** A dispatched endpoint run: its persisted run id plus the Future of its async chunk loop. */
    private record RunHandle(UUID runId, Future<RunStatus> future) {}

    /**
     * Core fan-out: resolves the connector's enabled endpoints, persists each endpoint's run row
     * (status {@code Running}) <em>synchronously</em> on the calling thread, then submits the chunk
     * loop for each on the vthread executor. Returns one handle per endpoint, in resolution order.
     */
    private List<RunHandle> submitRunHandles(UUID connectorId, RunTrigger trigger) {
        // Resolving endpoints (and creating run rows) touches the EntityManager and may lazily
        // create a synthetic endpoint — needs an active request context on the calling thread.
        List<UUID> endpointIds = resolveInRequestContext(connectorId);
        if (endpointIds.isEmpty()) {
            LOG.warnf("No runnable endpoint for connector %s — nothing to run", connectorId);
            return List.of();
        }
        List<RunHandle> handles = new java.util.ArrayList<>(endpointIds.size());
        for (UUID endpointId : endpointIds) {
            UUID runId = startRunRowInRequestContext(endpointId, trigger);
            if (runId == null) continue;  // endpoint/connector vanished between resolve and start
            Future<RunStatus> f = vt.submit(() -> executeEndpointRun(endpointId, runId, trigger));
            handles.add(new RunHandle(runId, f));
        }
        return handles;
    }

    /**
     * Fire-and-forget fan-out for callers that ignore the outcome (the scheduler). Submits the whole
     * cycle on the vthread executor and returns a single Future that resolves once EVERY endpoint run
     * has finished, to a coarse aggregate (the first non-Completed status, else Completed).
     */
    public Future<RunStatus> submitAsync(UUID connectorId, RunTrigger trigger) {
        return vt.submit(() -> {
            List<RunHandle> handles = submitRunHandles(connectorId, trigger);
            if (handles.isEmpty()) {
                return new RunStatus.Failed(Instant.now(),
                        "No runnable endpoint for connector: " + connectorId, false);
            }
            RunStatus summary = new RunStatus.Completed(Instant.now(), 0, Duration.ZERO);
            for (int i = 0; i < handles.size(); i++) {
                RunHandle h = handles.get(i);
                try {
                    RunStatus s = h.future().get();
                    if (!(s instanceof RunStatus.Completed) && summary instanceof RunStatus.Completed) {
                        summary = s;  // surface the first non-Completed
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    // Honor the interrupt: cancel the runs we haven't collected yet and stop. Continuing
                    // would just re-throw on every remaining get() (the interrupt flag is set) while the
                    // outstanding runs keep going untracked.
                    cancelOutstanding(handles, i);
                    return new RunStatus.Failed(Instant.now(), "interrupted", false);
                } catch (Exception e) {
                    summary = new RunStatus.Failed(Instant.now(), e.getMessage(), false);
                }
            }
            return summary;
        });
    }

    /**
     * Cancel the runs from index {@code from} onward when the aggregating wait is interrupted:
     * raise each run's cooperative cancel flag (the chunk loop checks it and finalizes as Cancelled)
     * and interrupt its worker to unblock any in-flight wait.
     */
    private void cancelOutstanding(List<RunHandle> handles, int from) {
        for (int j = from; j < handles.size(); j++) {
            RunHandle h = handles.get(j);
            cancelFlags.put(h.runId(), Boolean.TRUE);
            h.future().cancel(true);
        }
    }

    /**
     * Async worker for one endpoint: continues the pre-created {@code runId}. Activates a request
     * context, sets tenant, acquires the per-endpoint advisory lock, and drives the chunk loop.
     * NEVER swallows a failure — any escaping exception finalizes the run row as {@code Failed}.
     */
    private RunStatus executeEndpointRun(UUID endpointId, UUID runId, RunTrigger trigger) {
        var requestContext = Arc.container().requestContext();
        requestContext.activate();
        try {
            return runSyncEndpoint(endpointId, runId, trigger);
        } catch (Exception e) {
            LOG.errorf(e, "Connector run %s (endpoint %s) failed unexpectedly", runId, endpointId);
            TenantContext.callAsSystem(() -> {
                finalizeRun(runId, STATUS_FAILED, e.getMessage(), e);
                return null;
            });
            return new RunStatus.Failed(Instant.now(), e.getMessage(), false);
        } finally {
            // Catch-all drain: completeRun/failRun/cancelRun already drain on normal endings
            // (idempotent — remove()); this also covers the unexpected-exception path above, which
            // finalizes the run FAILED outside those helpers and would otherwise leak the stats map.
            stats.drain(runId);
            requestContext.deactivate();
        }
    }

    private List<UUID> resolveInRequestContext(UUID connectorId) {
        var requestContext = Arc.container().requestContext();
        boolean activatedHere = !requestContext.isActive();
        if (activatedHere) requestContext.activate();
        try {
            return TenantContext.callAsSystem(() -> endpointResolver.resolveEnabled(connectorId));
        } finally {
            if (activatedHere) requestContext.deactivate();
        }
    }

    /**
     * Connector-keyed synchronous run, kept for callers/tests that drive a (single-endpoint)
     * flat connector and expect one RunStatus back. Resolves the connector's endpoints and runs
     * them synchronously on the current thread; returns the first endpoint's status (single
     * endpoint is the flat case), or the first non-Completed status across endpoints.
     */
    public RunStatus runSync(UUID connectorId, RunTrigger trigger) {
        List<UUID> endpointIds = resolveInRequestContext(connectorId);
        if (endpointIds.isEmpty()) {
            return new RunStatus.Failed(Instant.now(),
                    "No runnable endpoint for connector: " + connectorId, false);
        }
        RunStatus result = null;
        for (UUID eid : endpointIds) {
            UUID runId = startRunRowInRequestContext(eid, trigger);
            RunStatus s;
            try {
                s = runId == null
                        ? new RunStatus.Failed(Instant.now(), "Endpoint not found: " + eid, false)
                        : runSyncEndpoint(eid, runId, trigger);
            } finally {
                // Same catch-all as the async worker: drain even if runSyncEndpoint throws
                // unexpectedly (idempotent on normal endings).
                if (runId != null) stats.drain(runId);
            }
            if (result == null || (result instanceof RunStatus.Completed && !(s instanceof RunStatus.Completed))) {
                result = s;
            }
        }
        return result;
    }

    /**
     * Creates (persists) the {@code connector_run} row for an endpoint synchronously and returns its
     * id, so callers can dereference the run before its chunk loop runs. Returns {@code null} if the
     * endpoint or its connector cannot be loaded. Needs an active request context on the caller.
     *
     * <p>The entire body runs inside a {@code REQUIRES_NEW} transaction so the Agroal interceptor
     * fires and sets the tenant GUC before any table scan. Without this, {@code findById} on an
     * RLS-enforced datasource (no active JTA tx on the caller) acquires a connection in auto-commit
     * mode where the interceptor does not fire, the GUC remains unset, and RLS returns null for
     * every row — causing the run row to never be created (finding lm-connector-run-rls-404, 422
     * follow-on).
     */
    private UUID startRunRowInRequestContext(UUID endpointId, RunTrigger trigger) {
        var requestContext = Arc.container().requestContext();
        boolean activatedHere = !requestContext.isActive();
        if (activatedHere) requestContext.activate();
        try {
            return TenantContext.callAsSystem(() ->
                QuarkusTransaction.requiringNew().call(() -> {
                    ConnectorEndpoint endpoint = endpoints.findById(endpointId);
                    if (endpoint == null) return null;
                    Connector c = connectors.findById(endpoint.getConnectorId());
                    if (c == null) return null;
                    return startRunRow(c, endpoint, trigger).getId();
                })
            );
        } finally {
            if (activatedHere) requestContext.deactivate();
        }
    }

    /**
     * Runs ONE endpoint synchronously against a pre-created run row: loads endpoint + connector,
     * sets tenant, delegates the chunk loop.
     *
     * <p>The initial entity lookups run inside their own {@code REQUIRES_NEW} transactions so the
     * Agroal interceptor fires and sets the tenant GUC. Without a JTA transaction the interceptor
     * does not fire; on an RLS-enforced datasource the GUC would remain unset and every row lookup
     * would return null (finding lm-connector-run-rls-404, async-404 follow-on).
     */
    public RunStatus runSyncEndpoint(UUID endpointId, UUID runId, RunTrigger trigger) {
        ConnectorEndpoint endpoint = TenantContext.callAsSystem(() ->
                QuarkusTransaction.requiringNew()
                        .call(() -> endpoints.findById(endpointId)));
        if (endpoint == null) {
            return new RunStatus.Failed(Instant.now(), "Endpoint not found: " + endpointId, false);
        }
        final UUID connectorId = endpoint.getConnectorId();
        Connector c = TenantContext.callAsSystem(() ->
                QuarkusTransaction.requiringNew()
                        .call(() -> connectors.findById(connectorId)));
        if (c == null) {
            return new RunStatus.Failed(Instant.now(),
                    "Connector not found: " + connectorId, false);
        }
        TenantContextFilter.CURRENT_TENANT.set(c.getTenantId());
        try {
            return TenantContext.callAs(c.getTenantId(), () -> doRunEndpoint(c, endpoint, runId, trigger));
        } finally {
            TenantContextFilter.CURRENT_TENANT.remove();
        }
    }

    public boolean cancel(UUID runId) {
        if (runId == null) return false;
        cancelFlags.put(runId, true);
        return true;
    }

    /**
     * Loads the run row, then delegates the chunk to {@link ChunkExecutor}.
     *
     * <p>Deliberately NOT {@code @Transactional}: a transaction opened here would span the source
     * round-trip inside {@code executeChunk} and hold a pooled connection for its whole duration.
     * {@code ChunkExecutor} opens its own short transactions instead, and re-loads the run inside
     * the one that writes, so the counter mutations are still flushed at that commit.
     *
     * <p>The lookup keeps its own {@code REQUIRES_NEW} transaction so the Agroal interceptor fires
     * and sets the tenant GUC — without one the RLS-enforced datasource returns null.
     */
    protected ChunkResult executeChunkManaged(UUID runId, ConnectorType type,
                                              ConnectorEndpoint endpoint, ConnectorCursor cursor) {
        ConnectorRun run = QuarkusTransaction.requiringNew().call(() -> runs.findById(runId));
        if (run == null) return new ChunkResult.Failure("Run not found: " + runId, null, false);
        return chunkExecutor.executeChunk(run, type, endpoint, cursor);
    }

    @Transactional
    protected ConnectorRun startRunRow(Connector c, ConnectorEndpoint endpoint, RunTrigger trigger) {
        ConnectorRun run = new ConnectorRun();
        run.setTenantId(c.getTenantId());
        run.setConnectorId(c.getId());
        run.setEndpointId(endpoint.getId());
        run.setStatus("Running");
        run.setSyncMode(syncModeName(c.getSyncMode()));
        run.setStartedAt(Instant.now());
        run.setTriggeredBy(triggerName(trigger));
        run.setTriggeredMeta(mapper.valueToTree(trigger));
        ConnectorCursorEntity cursorEnt = cursors.findByEndpoint(endpoint.getId());
        run.setCursorBefore(cursorEnt != null ? cursorEnt.getCursorValue() : null);
        runs.persist(run);
        runs.flush();
        return run;
    }

    /**
     * The single place a run row reaches a terminal status — every ending (Completed, Failed,
     * Cancelled) funnels through here. The endpoint's {@code last_run_status} and
     * {@code last_run_at} are stamped with the SAME string and the SAME instant in the SAME
     * transaction, so run row and endpoint can never disagree.
     */
    @Transactional
    protected void finalizeRun(UUID runId, String status, String errorMessage, Throwable errorStack) {
        ConnectorRun r = runs.findById(runId);
        if (r == null) return;
        Instant endedAt = Instant.now();
        r.setStatus(status);
        ingestionMetrics.recordRunFinished(status);
        r.setEndedAt(endedAt);
        if (errorMessage != null) r.setErrorMessage(errorMessage);
        if (errorStack != null)   r.setErrorStack(throwableToString(errorStack));
        ConnectorCursorEntity cursorEnt = r.getEndpointId() != null
                ? cursors.findByEndpoint(r.getEndpointId()) : null;
        if (cursorEnt != null) r.setCursorAfter(cursorEnt.getCursorValue());
        if (r.getEndpointId() != null) {
            ConnectorEndpoint endpoint = endpoints.findById(r.getEndpointId());
            if (endpoint != null) {
                endpoint.setLastRunStatus(status);
                endpoint.setLastRunAt(endedAt);
            }
        }
    }

    private RunStatus doRunEndpoint(Connector c, ConnectorEndpoint endpoint, UUID runId, RunTrigger trigger) {
        ConnectorType type;
        try {
            type = registry.find(c.getTypeId());
        } catch (Exception e) {
            TenantContext.callAsSystem(() -> { finalizeRun(runId, STATUS_FAILED, e.getMessage(), e); return null; });
            return new RunStatus.Failed(Instant.now(), e.getMessage(), false);
        }

        // Per-ENDPOINT advisory lock: serialize runs of the SAME endpoint across the whole
        // cluster (each endpoint owns one cursor row). Distinct endpoints of one connector run
        // in parallel (independent cursors, no shared state). Held on a dedicated connection for
        // the run's lifetime; chunk transactions use their own pooled connections. A pod crash
        // ends the session and Postgres releases the lock automatically. Non-blocking: a
        // redundant submission (scheduler re-fire, manual+schedule overlap) skips instead of
        // piling up.
        long lockKey = advisoryKey(endpoint.getId());
        Connection lockConn;
        try {
            lockConn = adminDs.getConnection();
        } catch (SQLException e) {
            TenantContext.callAsSystem(() -> { finalizeRun(runId, STATUS_FAILED, "advisory lock connection error: " + e.getMessage(), e); return null; });
            return new RunStatus.Failed(Instant.now(), "advisory lock connection error: " + e.getMessage(), true);
        }
        try {
            if (!tryAdvisoryLock(lockConn, lockKey)) {
                LOG.infof("Endpoint %s already has an active run — skipping this submission", endpoint.getId());
                // Known transient: this stamps the endpoint's last_run_status Cancelled while the OTHER
                // (live) run is still mid-flight; that run's own finalizeRun later overwrites it with its
                // real terminal status, so the endpoint self-heals — no action needed.
                TenantContext.callAsSystem(() -> { finalizeRun(runId, "Cancelled", "skipped: another run is already active for this endpoint", null); return null; });
                // defensive: nothing accumulated yet on this path, but keep the drain symmetric with complete/fail/cancel
                stats.drain(runId);
                // No advisory lock was acquired on this connection — nothing to unlock, but the
                // checkout itself must still be returned to the pool (unlike the success path,
                // which hands lockConn to releaseAdvisoryLock).
                closeQuietly(lockConn);
                return new RunStatus.Cancelled(Instant.now(),
                        "skipped: another run is already active for this endpoint");
            }
        } catch (SQLException e) {
            closeQuietly(lockConn);
            TenantContext.callAsSystem(() -> { finalizeRun(runId, STATUS_FAILED, "advisory lock error: " + e.getMessage(), e); return null; });
            return new RunStatus.Failed(Instant.now(), "advisory lock error: " + e.getMessage(), true);
        }

        try {
            return runChunks(c, endpoint, runId, trigger, type);
        } finally {
            releaseAdvisoryLock(lockConn, lockKey);
        }
    }

    private RunStatus runChunks(Connector c, ConnectorEndpoint endpoint, UUID runId, RunTrigger trigger, ConnectorType type) {
        ConnectorRun run = TenantContext.callAsSystem(() ->
                QuarkusTransaction.requiringNew()
                        .call(() -> runs.findById(runId)));
        events.emit("connector.run.started", Map.of(
                RUN_ID,       runId.toString(),
                "connector_id", c.getId().toString(),
                "endpoint_id",  endpoint.getId().toString(),
                "sync_mode",    run.getSyncMode(),
                "trigger",      run.getTriggeredBy()));

        long startedNs = System.nanoTime();
        try {
            while (true) {
                if (Boolean.TRUE.equals(cancelFlags.get(runId))) {
                    return cancelRun(runId);
                }

                ConnectorCursor cursor = currentCursor(endpoint.getId());
                ChunkResult result = TenantContext.callAsSystem(() ->
                        executeChunkManaged(runId, type, endpoint, cursor));

                switch (result) {
                    case ChunkResult.Success s -> {
                        // EndOfStream: no new records processed, or cursor unchanged. Otherwise HasMore → loop.
                        if (s.processed() == 0 || cursor.value().equals(s.nextCursor().value())) {
                            return completeRun(c, endpoint, runId, run, startedNs);
                        }
                    }
                    case ChunkResult.PartialSuccess ps -> {
                        // continue loop — partial success means more data may follow
                    }
                    case ChunkResult.Failure f -> {
                        return failRun(runId, f);
                    }
                }
            }
        } finally {
            cancelFlags.remove(runId);
        }
    }

    private RunStatus cancelRun(UUID runId) {
        TenantContext.callAsSystem(() -> {
            finalizeRun(runId, "Cancelled", "cancelled by user", null);
            return null;
        });
        stats.drain(runId); // drain-and-discard: prevent the in-JVM map leaking on a non-complete ending
        events.emit("connector.run.cancelled", Map.of(RUN_ID, runId.toString()));
        return new RunStatus.Cancelled(Instant.now(), "cancelled");
    }

    private RunStatus completeRun(Connector c, ConnectorEndpoint endpoint, UUID runId, ConnectorRun run, long startedNs) {
        TenantContext.callAsSystem(() -> {
            finalizeRun(runId, "Completed", null, null);
            return null;
        });
        ConnectorRun refreshed = TenantContext.callAsSystem(() ->
                QuarkusTransaction.requiringNew()
                        .call(() -> runs.findById(runId)));
        long recordsOut = refreshed != null ? refreshed.getRecordsOut() : run.getRecordsOut();
        events.emit("connector.run.completed", Map.of(
                RUN_ID,      runId.toString(),
                "endpoint_id", endpoint.getId().toString(),
                "records_in",  refreshed != null ? refreshed.getRecordsIn()  : run.getRecordsIn(),
                "records_out", recordsOut,
                "duration_ms", Duration.ofNanos(System.nanoTime() - startedNs).toMillis(),
                "tables",      stats.drain(runId)));
        // Stamp the event with the run's ACTUAL start (loaded at the top of runChunks, NOT NULL, set in
        // startRunRow before any chunk ran). This is the start of the refresher's schema-change look-back
        // window — an Instant.now() fallback here would start the window at finalization time and could
        // miss a table created earlier in the run, skipping the SchemaCard refresh.
        runCompleted.fireAsync(new ConnectorRunCompleted(
                c.getId(), c.getTenantId(), runId, run.getStartedAt()));
        return new RunStatus.Completed(
                Instant.now(),
                recordsOut,
                Duration.ofNanos(System.nanoTime() - startedNs));
    }

    private RunStatus failRun(UUID runId, ChunkResult.Failure f) {
        TenantContext.callAsSystem(() -> {
            finalizeRun(runId, STATUS_FAILED, f.message(), f.cause());
            return null;
        });
        stats.drain(runId); // drain-and-discard: prevent the in-JVM map leaking on a non-complete ending
        events.emit("connector.run.failed", Map.of(
                RUN_ID,        runId.toString(),
                "error_message", f.message() == null ? "" : f.message(),
                "retryable",     f.retryable()));
        return new RunStatus.Failed(Instant.now(), f.message(), f.retryable());
    }

    private ConnectorCursor currentCursor(UUID endpointId) {
        return TenantContext.callAsSystem(() ->
                // Wrap in REQUIRES_NEW so the Agroal interceptor fires and sets the tenant GUC
                // before the cursor read. The L1 cache is cleared inside the transaction so each
                // iteration reads the latest committed cursor value (avoids the single-run runaway
                // where a stale cached cursor would cause unbounded re-reads).
                QuarkusTransaction.requiringNew().call(() -> {
                    cursors.getEntityManager().clear();
                    ConnectorCursorEntity e = cursors.findByEndpoint(endpointId);
                    if (e == null || e.getCursorValue() == null || e.getCursorValue().isNull()) {
                        return ConnectorCursor.empty();
                    }
                    return new ConnectorCursor(e.getCursorValue(), e.getCapturedAt());
                })
        );
    }

    /** 64-bit advisory key from an endpoint UUID. Cross-endpoint collisions are astronomically
     *  rare and would only cause two unrelated endpoints to serialize occasionally — harmless. */
    private static long advisoryKey(UUID id) {
        return id.getMostSignificantBits() ^ id.getLeastSignificantBits();
    }

    private boolean tryAdvisoryLock(Connection conn, long key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            ps.setLong(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    /** Release the session-level lock BEFORE returning the connection to the pool (otherwise the
     *  lock would leak onto the reused backend session), then close. */
    private void releaseAdvisoryLock(Connection conn, long key) {
        if (conn == null) return;
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, key);
            ps.execute();
        } catch (SQLException e) {
            LOG.warnf("Failed to release advisory lock %d: %s", key, e.getMessage());
        } finally {
            closeQuietly(conn);
        }
    }

    private static void closeQuietly(Connection conn) {
        if (conn == null) return;
        try { conn.close(); } catch (SQLException ignored) { /* returning to pool */ }
    }

    private static String syncModeName(SyncMode m) {
        return switch (m) {
            case SyncMode.Incremental i -> "Incremental";
            case SyncMode.FullRefresh f -> "FullRefresh";
            case SyncMode.Snapshot s    -> "Snapshot";
        };
    }

    private static String triggerName(RunTrigger t) {
        return switch (t) {
            case RunTrigger.Schedule s -> "SCHEDULE";
            case RunTrigger.Manual m   -> "MANUAL";
            case RunTrigger.Event e    -> "EVENT";
            case RunTrigger.Replay r   -> "REPLAY";
        };
    }

    private static String throwableToString(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
