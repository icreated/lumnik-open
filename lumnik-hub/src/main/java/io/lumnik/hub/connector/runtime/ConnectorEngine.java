package io.lumnik.hub.connector.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.ConnectorType;
import io.lumnik.hub.connector.api.types.*;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.exceptions.SourceUnreachableException;
import io.lumnik.hub.connector.domain.ConnectorCursorEntity;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.connector.event.ConnectorEvents;
import io.lumnik.hub.connector.event.ConnectorRunCompleted;
import io.lumnik.hub.connector.event.RunTableStats;
import io.lumnik.hub.connector.registry.ConnectorTypeRegistry;
import io.lumnik.hub.scheduler.TenantScope;
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
import org.jboss.logging.MDC;
import org.jboss.logging.Logger;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@ApplicationScoped
public class ConnectorEngine {

    private static final String STATUS_FAILED = "Failed";
    private static final String STATUS_PARTIAL = "Partial";
    private static final String STATUS_COMPLETED = "Completed";
    private static final String RUN_ID = "run_id";
    /** MDC key, camelCase like its neighbours {@code tenantId}/{@code userId} — the event
     *  payload's {@link #RUN_ID} is snake_case because that contract is JSON, not a log. */
    private static final String MDC_RUN_ID = "runId";

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

    /**
     * The single registry answering BOTH "is this run's chunk loop in flight in THIS JVM?" (key
     * present) and "was cancellation requested?" (value). One map instead of two: a two-structure
     * split would leak an entry on the race between a cancel and a run that just ended — the run
     * that just finished removes itself from an "in flight" set while a concurrent cancel is still
     * writing to a separate "cancelled" set, and nothing ever cleans that second write up.
     */
    private final Map<UUID, Boolean> liveRuns = new ConcurrentHashMap<>();

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
        List<RunHandle> handles = new ArrayList<>(endpointIds.size());
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
     * Cancel the runs from index {@code from} onward when the aggregating wait is interrupted: raise
     * each run's cooperative cancel flag AND interrupt its worker to unblock any in-flight wait. The
     * two race, and the interrupt essentially always wins in practice: the flag is only honoured at
     * the loop's next top-of-chunk check ({@code runChunks}, {@code Cancelled}), while the interrupt
     * lands immediately, on a worker that in the overwhelmingly common case is mid-chunk — aborting
     * whatever blocking call was in flight and, from there, ending the run {@code Failed} through
     * {@code executeEndpointRun}'s catch (with a raw driver-level message as {@code error_message}).
     * The flag-honoured, cleanly-{@code Cancelled} outcome is real but narrow: only a worker caught
     * exactly between chunks when the interrupt lands.
     *
     * <p>{@code cancel(h.runId())}'s result is ignored on purpose: a handle whose chunk loop has not
     * registered in {@code liveRuns} yet (endpoint/connector still resolving, advisory lock not yet
     * acquired) has nothing to flag — {@code computeIfPresent} correctly declines rather than
     * resurrecting an entry for a run that, from this map's point of view, does not exist. That is
     * NOT a gap: {@code h.future().cancel(true)} right below still interrupts that worker.
     */
    private void cancelOutstanding(List<RunHandle> handles, int from) {
        for (int j = from; j < handles.size(); j++) {
            RunHandle h = handles.get(j);
            cancel(h.runId());
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
            // Two sources can reach this worker as a real Thread.interrupt(): cancelOutstanding()
            // via h.future().cancel(true), and onStop()'s vt.shutdownNow() at hub shutdown. Either
            // aborts whatever blocking JDBC call was in flight (surfacing HERE as this very
            // exception) but leaves the THREAD's interrupt status set. Uncleared, the FIRST casualty
            // on the way out is not this catch block but doRunEndpoint's own
            // finally { releaseAdvisoryLock(...) }, which unwinds BEFORE control reaches here: its
            // "SELECT pg_advisory_unlock(?)" would be poisoned the same way, fail, and get swallowed
            // to a WARN (releaseAdvisoryLock's own catch). That failure is harmless only on THIS
            // path: the interrupt physically kills the JDBC socket, so Agroal destroys the pooled
            // handle instead of recycling it — closeQuietly() right after does not itself end the
            // backend session or drop a session-level advisory lock, the destroyed connection does.
            // A releaseAdvisoryLock failure reached by any OTHER route (no interrupt, a live socket)
            // would leave the lock held and must not lean on this reasoning. What clearing the flag
            // HERE actually buys is a clean thread for what comes next in THIS block: the
            // finalizeRun() write below, on a freshly acquired connection, would otherwise be
            // poisoned the same way and throw again, leaving the row stuck at Running forever (no
            // in-JVM process ever revisits it; only RunReaper.reap() clears it, and only at the NEXT
            // boot). Clearing the interrupt also changes onStop()'s shutdown behaviour: finalizeRun()
            // is now attempted (marking the row Failed) instead of failing fast and leaving it
            // Running until the next boot — probably an improvement, but a real behaviour change
            // worth naming. The flag is restored right after finalizeRun() below, once the critical
            // write is safely past it: the suppression is scoped to that one write instead of being
            // swallowed for the rest of the thread's life, so correctness does not depend on vt being
            // newVirtualThreadPerTaskExecutor() — it holds for a pooled executor too.
            boolean interrupted = Thread.interrupted();
            LOG.errorf(e, "Connector run %s (endpoint %s) failed unexpectedly", runId, endpointId);
            TenantContext.callAsSystem(() -> {
                finalizeRun(runId, STATUS_FAILED, e.getMessage(), e);
                return null;
            });
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
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
        // Everything this run does happens under its tenant AND under its identity in the log.
        // TenantScope sets both ThreadLocals — the Hibernate filter's and the RLS GUC's — plus the
        // tenantId MDC, and RESTORES what was there; the hand-rolled pair this replaces removed
        // instead, which lost a synchronous caller's tenant on the way out. runId rides alongside so
        // every line raised mid-run says which run raised it: the sixteen reader/writer WARNs named
        // neither the run nor the tenant, and %X{tenantId} — already in the console format — printed
        // empty for everything a run wrote, because no one on this thread ever filled it.
        MDC.put(MDC_RUN_ID, runId.toString());
        try {
            return TenantScope.get(c.getTenantId(), () -> doRunEndpoint(c, endpoint, runId, trigger));
        } finally {
            MDC.remove(MDC_RUN_ID);
        }
    }

    /**
     * Requests cancellation of {@code runId}. Cooperative: the flag is read once, at the top of the
     * chunk loop, so an in-flight chunk always finishes before the run ends — nothing here claims
     * the run IS cancelled, only that cancellation was requested and takes effect at the next chunk
     * boundary.
     *
     * <p>Atomic against the run ending concurrently: {@code computeIfPresent} only flags an entry
     * that is still present, so a run whose chunk loop already exited (and removed itself from
     * {@link #liveRuns}) cannot be resurrected into "cancelling" — it simply is not there.
     *
     * @return {@code false} for a {@code null} id or when no chunk loop in THIS JVM is watching
     *         {@code runId} (never started here, or already ended);
     *         {@code true} once cancellation has been recorded for a run this JVM is driving.
     */
    public boolean cancel(UUID runId) {
        if (runId == null) return false;
        return liveRuns.computeIfPresent(runId, (k, v) -> Boolean.TRUE) != null;
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
                                              ConnectorEndpoint endpoint, ConnectorCursor cursor,
                                              RunBound bound) {
        ConnectorRun run = QuarkusTransaction.requiringNew().call(() -> runs.findById(runId));
        if (run == null) return new ChunkResult.Failure("Run not found: " + runId, null, false);
        return chunkExecutor.executeChunk(run, type, endpoint, cursor, bound);
    }

    /**
     * The run's target, measured once before the first chunk.
     *
     * <p>A type that cannot reach its source here degrades to an UNBOUNDED run with a WARN, which is
     * exactly how every run behaved before targets existed. Measuring a target is a diagnostic, and
     * a diagnostic must not be the thing that turns a working ingestion into a failed run — the
     * first chunk will fail on its own, with the source's own message, if the source is really
     * unreachable. THE POLICY LIVES HERE, once, rather than in each type.
     *
     * <p>The catch is deliberately wide, so it also absorbs a broken config — and that is why the
     * line names the exception's TYPE. {@code getMessage()} alone is {@code null} for an NPE, which
     * printed "reading unbounded — null" and told the operator nothing about which of the two had
     * happened. The consequence stays bounded either way: a config this cannot survive is a config
     * the first chunk cannot survive either, so the run then fails with the reader's own sentence.
     *
     * <p>ONE exception is not absorbed. {@link SourceUnreachableException} is a type saying it
     * already holds the reader's answer — the source cannot be reached — and degrading there buys
     * nothing but a second full timeout for the same sentence: measured against a dead broker,
     * 60 s to say "reading unbounded" and 60 s more to fail, with 198 client WARN lines between
     * them (#486). It travels up to {@code runChunks}, which fails the run at once.
     */
    private RunStart runStartOf(ConnectorRun run, Connector c, ConnectorType type,
                                ConnectorEndpoint endpoint, ConnectorCursor cursor) {
        try {
            return chunkExecutor.runStart(run, c, type, endpoint, cursor);
        } catch (SourceUnreachableException unreachable) {
            throw unreachable;
        } catch (Exception e) {
            LOG.warnf(e, "run %s: could not measure the run's target, reading unbounded — %s: %s",
                    run.getId(), e.getClass().getSimpleName(), e.getMessage());
            return RunStart.none();
        }
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
        ConnectorEndpoint endpoint = r.getEndpointId() != null ? endpoints.findById(r.getEndpointId()) : null;
        if (endpoint != null) {
            endpoint.setLastRunStatus(status);
            endpoint.setLastRunAt(endedAt);
        }
        String reason = errorMessage != null && !errorMessage.isBlank() ? " — " + errorMessage : "";
        Object endpointLabel = endpoint != null ? endpoint.getName() : r.getEndpointId();   // the id when the row is gone
        // Partial is a WARN with Failed: both are endings the operator has to notice. An INFO line
        // is what made the two silences this arc closes invisible in the first place.
        LOG.logf(STATUS_FAILED.equals(status) || STATUS_PARTIAL.equals(status)
                        ? Logger.Level.WARN : Logger.Level.INFO,
                "run %s %s: endpoint %s, in=%d out=%d skipped=%d cell_errors=%d chunks=%d, %d ms%s",
                runId, status, endpointLabel, r.getRecordsIn(), r.getRecordsOut(),
                r.getRecordsSkipped(), r.getCellErrors(), r.getChunkCount(),
                Duration.between(r.getStartedAt(), endedAt).toMillis(), reason);
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
            return runChunks(c, endpoint, runId, type);
        } finally {
            releaseAdvisoryLock(lockConn, lockKey);
        }
    }

    /**
     * The chunk loop. Package-private, not private: {@code ChunkLoopNoProgressNetIT} drives it with
     * a reader that never advances, which is the one shape no real connector can be asked to
     * produce on demand. A test that copied this loop would be testing the copy.
     *
     * <p>The {@code trigger} the caller holds is not a parameter: the log line and the started
     * event both read it back off the run row ({@code run.getTriggeredBy()}), which is the single
     * spelling every other reader sees.
     *
     * <p>Must be called with NO transaction active — the production shape. The no-progress net below
     * reads the cursor back out of Postgres between chunks, and {@code ChunkExecutor} joins an
     * ambient transaction when it finds one: driven from inside one, the chunk's cursor write would
     * not be committed yet, every read would return the pre-chunk value and EVERY run would fail
     * after one chunk. Neighbouring ITs do drive {@code executeChunk} inside a transaction on
     * purpose; this entry point is not that one.
     */
    RunStatus runChunks(Connector c, ConnectorEndpoint endpoint, UUID runId, ConnectorType type) {
        ConnectorRun run = TenantContext.callAsSystem(() ->
                QuarkusTransaction.requiringNew()
                        .call(() -> runs.findById(runId)));
        // The row can be gone: deleting a connector removes its runs (V26) and the reaper clears
        // rows at boot, both racing a run already under way. Unguarded, the very next line threw an
        // NPE that left the chunk loop entirely — on the synchronous entry point nothing catches it
        // at all (the caller gets the exception and the row, when there is one, stays Running until
        // the next boot); on the async worker executeEndpointRun's last-resort catch finalizes it
        // Failed while BYPASSING failRun, so no connector.run.failed is emitted and every watcher
        // sees the run go silent. The shape ChunkExecutor carried one layer down until #560, and
        // the sentence is the one executeChunkManaged already writes for this very read.
        // finalizeRun then finds no row to stamp and returns; the ending is still ANNOUNCED,
        // because whoever asked for this run is owed it either way. "Announced" holds on the
        // production path and only there: runSyncEndpoint has put this thread in TenantScope, and
        // EventOutboxService.publish drops an event raised with no tenant in context. That is why
        // executeEndpointRun's catch, which runs OUTSIDE that scope, would still emit nothing if it
        // were routed through failRun — and why an IT driving runChunks directly must enter the
        // scope to observe the event at all.
        if (run == null) {
            return failRun(runId, new ChunkResult.Failure("Run not found: " + runId, null, false));
        }
        // paired with the end line in finalizeRun, whatever the ending
        LOG.infof("run %s started: connector %s (%s), endpoint %s, trigger %s",
                runId, c.getName(), c.getTypeId(), endpoint.getName(), run.getTriggeredBy());
        events.emit("connector.run.started", Map.of(
                RUN_ID,       runId.toString(),
                "connector_id", c.getId().toString(),
                "endpoint_id",  endpoint.getId().toString(),
                "sync_mode",    run.getSyncMode(),
                "trigger",      run.getTriggeredBy()));

        liveRuns.put(runId, Boolean.FALSE);
        try {
            ConnectorCursor cursor = currentCursor(endpoint.getId());
            // THE RUN'S TARGET, measured once, before the first chunk: what the source held when
            // this run started. Without one a run against a live source never ends — it stops when
            // the PRODUCER stops, not when the reader has caught up — so the run row stays Running
            // for days, every scheduled slot behind it is skipped by the advisory lock above, and a
            // declared `window` fence is walked straight through by a run that began inside it.
            // Types that declare no target (csv, rest, hello-world) read exactly as they always did.
            final ConnectorCursor startedFrom = cursor;
            RunStart start;
            try {
                start = TenantContext.callAsSystem(() ->
                        runStartOf(run, c, type, endpoint, startedFrom));
            } catch (SourceUnreachableException unreachable) {
                // Retryable comes off the type, not off a literal: a source that is down is a
                // TransientFailureException, and ChunkExecutor reads the same property the same way.
                return failRun(runId, new ChunkResult.Failure(
                        unreachable.getMessage(), unreachable.getCause(), unreachable.isRetryable()));
            }
            final RunBound bound = start.bound();
            if (bound != null) {
                LOG.infof("run %s target: %s", runId, bound.describe());
            }
            while (true) {
                if (Boolean.TRUE.equals(liveRuns.get(runId))) {
                    return cancelRun(runId);
                }

                ConnectorCursor before = cursor;
                ChunkResult result = TenantContext.callAsSystem(() ->
                        executeChunkManaged(runId, type, endpoint, before, bound));

                int processed;
                switch (result) {
                    case ChunkResult.Success s -> {
                        // Terminal, or nothing read: either way this run is done. A terminal chunk
                        // may still be holding records and have persisted no position — that is a
                        // reader's right, not a stall, and only the chunk itself can tell them apart.
                        if (s.endOfStream() || s.processed() == 0) {
                            // A run that BEGAN by skipping what the source deleted underneath its
                            // cursor did not read everything it was entitled to, however cleanly
                            // the rest of it went. Reporting it Completed is the silence #467
                            // measured and left green. The sentences are not re-logged here: the
                            // reader's own planStart words them on the chunk that applies the
                            // repair, and the run row carries them as its message.
                            return start.lostAtStart().isEmpty()
                                    ? completeRun(c, endpoint, runId, run)
                                    : partialRun(c, endpoint, runId, run,
                                            String.join("; ", start.lostAtStart()));
                        }
                        processed = s.processed();
                    }
                    case ChunkResult.PartialSuccess ps -> processed = ps.processed();
                    case ChunkResult.ShortOfTarget st -> {
                        // Neither dry nor broken: the reader stopped for a reason of its own while
                        // the target says there is more. Everything it read was written and its
                        // position is persisted, so this is a terminal ending like the others — it
                        // simply is not a complete one, and saying Completed here would be the
                        // green run this whole arc exists to remove.
                        // BOTH sentences, when both are true. A run can begin past retention-deleted
                        // offsets AND stop on its chunk budget, and the pages tell the operator to
                        // read `error:` to learn WHICH cause fired — so keeping only the last one
                        // answers a question with half an answer.
                        return partialRun(c, endpoint, runId, run,
                                Stream.concat(start.lostAtStart().stream(), Stream.of(st.detail()))
                                        .collect(Collectors.joining("; ")));
                    }
                    case ChunkResult.Failure f -> {
                        return failRun(runId, f);
                    }
                }

                // THE NO-PROGRESS NET: records were read, so the position must have moved. Both
                // sides of this comparison are read back OUT of Postgres, one chunk apart. The
                // reader's own nextCursor is deliberately not used: it is a node built in the JVM,
                // and comparing it against one that came out of jsonb is comparing across a
                // serialization boundary — a POJONode (every jdbc cursor) is never equal to
                // anything parsed, and a LongNode comes back an IntNode. Both made this net answer
                // "it moved" for ever. A jdbc endpoint that started declaring its own cursor
                // foreign then re-read the same window until the pod died: 2 279 500 rows off a
                // 700-row table, in a minute.
                //
                // Failed, not Completed: every chunk that gets here PROMISED more, and no shipped
                // reader promises more from a position it did not move. (A reader that ends loaded
                // without persisting a position is the healthy case, and it was handled above — it
                // says endOfStream instead of leaving the engine to guess from the cursor.) So a
                // stopped position is never a normal ending here, and reporting Completed would
                // hide a re-reading endpoint behind a green run.
                cursor = currentCursor(endpoint.getId());
                if (cursor.value().equals(before.value())) {
                    return failRun(runId, new ChunkResult.Failure(
                            "endpoint '" + endpoint.getName() + "' read " + processed
                                    + " records but its stored position did not move — the same"
                                    + " window would be re-read for ever", null, false));
                }
            }
        } finally {
            liveRuns.remove(runId);
        }
    }

    /**
     * A run that ended without reaching the target it gave itself. Terminal, like every other
     * ending, and deliberately NOT Failed: nothing went wrong, everything read was written and the
     * position is persisted — the next run resumes from it and catches up.
     *
     * <p>{@code RunStatus.Partial} has existed since the connector runtime was written and nothing
     * ever produced it. {@code connector_run.status} carries no constraint (only two partial indexes
     * on {@code 'Running'}), so this needs no migration.
     *
     * <p>Partial means ONE thing: the run stopped below its target — a shortfall in POSITION. A
     * shortfall in DELIVERY (rows read and dropped) is not this; {@code records_skipped}, the
     * dead-letter list and OTK Law 1 already say it in full, and a status that answered both
     * questions would end up lying to one of them.
     */
    private RunStatus partialRun(Connector c, ConnectorEndpoint endpoint, UUID runId,
                                 ConnectorRun run, String detail) {
        Ended e = finishRun(c, endpoint, runId, run, STATUS_PARTIAL, detail, "connector.run.partial");
        return new RunStatus.Partial(e.endedAt(), e.recordsOut(), e.skipped());
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

    /**
     * ONE source of duration. The run's JOURNAL span — {@code ended_at − started_at} on the
     * {@code connector_run} row — is what every other reader already derives: {@code lm run list},
     * the TUI's DURATION column and {@code GET /api/platform/runs} all read the stored columns, and
     * the end-of-run log line in {@link #finalizeRun} uses those same two instants a moment before
     * Postgres rounds {@code ended_at} on the way in. Same span, one rounding apart. The event says
     * the same thing instead of measuring the chunk loop a second time, which reported a shorter,
     * different segment — it began after the row was created, the endpoint resolved and the
     * advisory lock taken, so the two numbers disagreed for every run that ever completed.
     */
    private RunStatus completeRun(Connector c, ConnectorEndpoint endpoint, UUID runId, ConnectorRun run) {
        Ended e = finishRun(c, endpoint, runId, run, STATUS_COMPLETED, null, "connector.run.completed");
        return new RunStatus.Completed(e.endedAt(), e.recordsOut(), e.elapsed());
    }

    /** What a terminal ending measured, so each status can shape its own {@link RunStatus}. */
    private record Ended(long recordsOut, long skipped, Instant endedAt, Duration elapsed) {}

    /**
     * ONE terminal ending for the two statuses that mean "this run wrote everything it read":
     * Completed and Partial. They are together because they must do the SAME six things, and the
     * first draft of Partial did four of them — it skipped the per-table counters and, worse, the
     * {@code runCompleted} event that starts the schema-card refresh window. A Partial run writes
     * rows (#467's shape wrote 3 000 of 5 000), so its tables need indexing exactly as much; the
     * Ask-to-SQL card would have gone stale for every partial run, silently.
     *
     * <p>{@code topic} is passed, not derived from {@code status}: a reader grepping for
     * {@code connector.run.partial} has to find the string, and a computed event name is one more
     * signal standing in for a question nobody asked it.
     */
    private Ended finishRun(Connector c, ConnectorEndpoint endpoint, UUID runId, ConnectorRun run,
                            String status, String detail, String topic) {
        TenantContext.callAsSystem(() -> {
            finalizeRun(runId, status, detail, null);
            return null;
        });
        ConnectorRun refreshed = TenantContext.callAsSystem(() ->
                QuarkusTransaction.requiringNew()
                        .call(() -> {
                            // finalizeRun wrote this row through the SAME persistence context, so a plain
                            // find hands back its in-memory copy — nanosecond Instants the database rounded
                            // on the way in. Clearing makes this a real re-read, so the span below is the one
                            // a reader of the stored columns computes, to the millisecond.
                            runs.getEntityManager().clear();
                            return runs.findById(runId);
                        }));
        long recordsOut = refreshed != null ? refreshed.getRecordsOut() : run.getRecordsOut();
        long recordsSkipped = refreshed != null ? refreshed.getRecordsSkipped() : run.getRecordsSkipped();
        Instant startedAt = refreshed != null ? refreshed.getStartedAt() : run.getStartedAt();
        Instant endedAt = refreshed != null && refreshed.getEndedAt() != null
                ? refreshed.getEndedAt()
                : Instant.now();
        Duration elapsed = Duration.between(startedAt, endedAt);
        Map<String, Object> payload = new HashMap<>();
        payload.put(RUN_ID, runId.toString());
        payload.put("endpoint_id", endpoint.getId().toString());
        payload.put("records_in", refreshed != null ? refreshed.getRecordsIn() : run.getRecordsIn());
        payload.put("records_out", recordsOut);
        payload.put("duration_ms", elapsed.toMillis());
        payload.put("tables", stats.drain(runId));
        // Only the endings that have something to explain carry it, so a `completed` payload keeps
        // exactly the keys its subscribers already read.
        if (detail != null) payload.put("error_message", detail);
        events.emit(topic, payload);
        // Stamp the event with the run's ACTUAL start (NOT NULL, set in startRunRow before any chunk
        // ran). This is the start of the refresher's schema-change look-back window — an Instant.now()
        // fallback here would start the window at finalization time and could miss a table created
        // earlier in the run, skipping the SchemaCard refresh.
        runCompleted.fireAsync(new ConnectorRunCompleted(
                c.getId(), c.getTenantId(), runId, startedAt));
        return new Ended(recordsOut, recordsSkipped, endedAt, elapsed);
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

    /**
     * Package-private, not private: {@code JdbcCompositeKeyCursorIT} drives it directly. A test that
     * copies this method tests the copy — and the copy would keep passing while a change here (a
     * cache that is no longer cleared, a different read path) broke the caller.
     */
    ConnectorCursor currentCursor(UUID endpointId) {
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
