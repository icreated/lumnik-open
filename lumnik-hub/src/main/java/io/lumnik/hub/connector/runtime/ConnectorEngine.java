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
import io.lumnik.hub.connector.domain.ConnectorRunStatus;
import io.lumnik.hub.connector.event.ConnectorEvents;
import io.lumnik.hub.connector.event.ConnectorRunCompleted;
import io.lumnik.hub.connector.event.RunTableStats;
import io.lumnik.hub.connector.registry.ConnectorTypeRegistry;
import io.lumnik.hub.scheduler.TenantScope;
import io.lumnik.hub.connector.repository.ConnectorCursorEntityRepository;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.lumnik.hub.run.RunLock;
import io.lumnik.hub.run.RunQueueSettings;
import io.lumnik.hub.sql.SqlFailureText;
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
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.MDC;
import org.jboss.logging.Logger;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.lumnik.hub.connector.domain.ConnectorRunStatus.CANCELLED;
import static io.lumnik.hub.connector.domain.ConnectorRunStatus.COMPLETED;
import static io.lumnik.hub.connector.domain.ConnectorRunStatus.FAILED;
import static io.lumnik.hub.connector.domain.ConnectorRunStatus.PARTIAL;

@ApplicationScoped
public class ConnectorEngine {

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
    @Inject RunQueueSettings runQueue;

    private ExecutorService vt;

    /**
     * #897: how many endpoint runs the SCHEDULER's fan-out ({@link #submitAsync}) drives at once ON
     * THIS HUB INSTANCE. Each holds one pooled {@code admin} connection for its whole life (its
     * {@link RunLock}) and borrows one more at a time for its chunk writes ({@code DynamicTableWriter}
     * keeps each write on ONE connection). Unbounded, a 25-table {@code jdbc-generic} manifest alone
     * filled the default pool of 20 with locks, every chunk write timed out, and the endpoints started
     * last failed with "advisory lock error". The default 4 takes at most 8 of those 20 slots and
     * leaves the rest to scheduled exports, on-demand rebuilds, manual runs (not counted, below) and
     * everything that borrows a slot for a moment; raise it with
     * {@code quarkus.datasource."admin".jdbc.max-size}, two slots per run.
     *
     * <p>With the run queue off the manual door ({@link #submitRun}) takes no permit and cannot wait: it
     * answers with one {@code Running} row per endpoint, and a row is committed {@code Running} only by a run that
     * already holds its lock ({@link RunReaper} fails a free one). With {@code lumnik.run-queue.enabled} it queues a
     * row instead and the {@link ConnectorRunDispatcher} draws on THIS SAME budget (#952, D11). Per instance, like
     * {@code max-concurrent-rebuilds}: N replicas allow N &times; this value, each against its own pool.
     */
    @ConfigProperty(name = "lumnik.connector.max-concurrent-scheduled-runs", defaultValue = "4")
    int maxConcurrentScheduledRuns;

    /** Fair, so one connector's long fan-out does not starve another's waiting turn. */
    private Semaphore scheduledRunPermits;

    /**
     * Connectors whose scheduled fan-out is still starting its endpoints in THIS JVM, with how many
     * such fan-outs each has. An endpoint waiting for a permit has no run row yet, so the connector
     * still reads as due to {@link ConnectorScheduler}'s cron check, which asks
     * {@link #hasPendingFanOut} rather than stack another fan-out on it every tick.
     */
    private final Map<UUID, Integer> pendingFanOuts = new ConcurrentHashMap<>();

    /**
     * The single registry answering BOTH "is this run's chunk loop in flight in THIS JVM?" (key
     * present) and "was cancellation requested?" (value). One map instead of two: a two-structure
     * split would leak an entry on the race between a cancel and a run that just ended — the run
     * that just finished removes itself from an "in flight" set while a concurrent cancel is still
     * writing to a separate "cancelled" set, and nothing ever cleans that second write up.
     */
    private final Map<UUID, Boolean> liveRuns = new ConcurrentHashMap<>();

    /** Test seam: runs in the queue transaction after the in-flight pre-check passed and before the INSERT. */
    private volatile Runnable beforeQueueInsert = () -> {};

    void beforeQueueInsert(Runnable hook) {
        this.beforeQueueInsert = hook;
    }

    void onStart(@Observes StartupEvent ev) {
        if (maxConcurrentScheduledRuns < 1) {
            // Zero permits would park every scheduled fan-out for ever, without a word.
            throw new IllegalStateException("lumnik.connector.max-concurrent-scheduled-runs must be at least 1, was "
                    + maxConcurrentScheduledRuns);
        }
        vt = Executors.newVirtualThreadPerTaskExecutor();
        scheduledRunPermits = new Semaphore(maxConcurrentScheduledRuns, true);
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
     * <p>With {@code lumnik.run-queue.enabled} (#952) the door starts nothing itself: each endpoint's row is
     * written {@code Queued} (no lock, no worker) and the {@link ConnectorRunDispatcher} starts it as a slot
     * frees; an endpoint that already has a queued or running run gets a row ended {@code Cancelled} at birth.
     *
     * <p>The async worker can never swallow a failure: any thrown exception finalizes the run row as
     * {@code Failed} (with message + stack) and is logged at ERROR, so the outcome is always
     * observable on the run row.
     *
     * @return the persisted run ids, one per enabled endpoint (empty if the connector has none).
     */
    public List<UUID> submitRun(UUID connectorId, RunTrigger trigger) {
        if (runQueue.enabled()) return queueRuns(connectorId, trigger);
        return submitRunHandles(connectorId, trigger, null).stream().map(RunHandle::runId).toList();
    }

    /**
     * The manual door with {@code lumnik.run-queue.enabled} (#952, D13): one durable {@code Queued} row per enabled
     * endpoint and nothing else — no lock, no permit, no worker. The dispatcher starts the rows, on this replica
     * (the door wakes it) or another.
     */
    private List<UUID> queueRuns(UUID connectorId, RunTrigger trigger) {
        List<UUID> endpointIds = resolveInRequestContext(connectorId);
        if (endpointIds.isEmpty()) {
            LOG.warnf("No runnable endpoint for connector %s — nothing to run", connectorId);
            return List.of();
        }
        List<UUID> ids = new ArrayList<>(endpointIds.size());
        for (UUID endpointId : endpointIds) {
            UUID id = queueEndpointRun(endpointId, trigger);
            if (id != null) ids.add(id);   // null: vanished since the resolve
        }
        return ids;
    }

    /**
     * Pre-checks the in-flight rule so the usual double click costs no failed INSERT; the unique index still decides a
     * race, and the loser ends at birth like any run that meets an in-flight one.
     */
    private UUID queueEndpointRun(UUID endpointId, RunTrigger trigger) {
        var requestContext = Arc.container().requestContext();
        boolean activatedHere = !requestContext.isActive();
        if (activatedHere) requestContext.activate();
        try {
            try {
                return TenantContext.callAsSystem(() -> QuarkusTransaction.requiringNew().call(() -> {
                    ConnectorEndpoint endpoint = endpoints.findById(endpointId);
                    if (endpoint == null) return null;
                    Connector c = connectors.findById(endpoint.getConnectorId());
                    if (c == null) return null;
                    if (runs.count("endpointId = ?1 and status in (?2, ?3)", endpointId,
                            ConnectorRunStatus.QUEUED.sqlValue(), ConnectorRunStatus.RUNNING.sqlValue()) > 0) {
                        return endedAtBirth(c, endpoint, trigger, null).runId();
                    }
                    beforeQueueInsert.run();
                    ConnectorRun run = newRunRow(c, endpoint, trigger);
                    run.setStatus(ConnectorRunStatus.QUEUED.sqlValue());
                    run.setStartedAt(null);       // set once, on leaving the queue (V68)
                    run.setCursorBefore(null);    // the start re-reads the cursor it resumes from
                    run.setQueuedAt(Instant.now());
                    run.setDefinitionSha256(DefinitionHash.of(c, endpoint));
                    runs.persist(run);
                    runs.flush();
                    return run.getId();
                }));
            } catch (RuntimeException e) {
                if (!isInFlightConflict(e)) throw e;
                StartedRun born = bornCancelled(endpointId, trigger);
                return born == null ? null : born.runId();   // null: the endpoint vanished between the conflict and now
            }
        } finally {
            if (activatedHere) requestContext.deactivate();
        }
    }

    /** A dispatched endpoint run: its persisted run id plus the Future of its async chunk loop. */
    private record RunHandle(UUID runId, Future<RunStatus> future) {}

    /**
     * Core fan-out: resolves the connector's enabled endpoints, persists each endpoint's run row
     * (status {@code Running}) <em>synchronously</em> on the calling thread, then submits the chunk
     * loop for each on the vthread executor. Returns one handle per endpoint, in resolution order.
     *
     * <p>{@code scheduledAt} is set for the scheduler's fan-out (#897), when it was submitted, and
     * null for the manual door, which is unbounded. Each scheduled endpoint first waits for a permit,
     * holding nothing, and only then takes its lock and writes its row — a waiting endpoint has no
     * row rather than a {@code Running} one with a free lock, which the reaper would fail. An
     * interrupt while waiting stops the fan-out; the endpoints still waiting get no row, and the
     * restored interrupt flag tells {@link #submitAsync} to cancel the ones that started.
     */
    private List<RunHandle> submitRunHandles(UUID connectorId, RunTrigger trigger, Instant scheduledAt) {
        // Resolving endpoints (and creating run rows) touches the EntityManager and may lazily
        // create a synthetic endpoint — needs an active request context on the calling thread.
        List<UUID> endpointIds = resolveInRequestContext(connectorId);
        if (endpointIds.isEmpty()) {
            LOG.warnf("No runnable endpoint for connector %s — nothing to run", connectorId);
            return List.of();
        }
        List<RunHandle> handles = new ArrayList<>(endpointIds.size());
        boolean bounded = scheduledAt != null;
        for (UUID endpointId : endpointIds) {
            if (bounded && !awaitScheduledRunPermit()) break;
            RunHandle h = startEndpointRun(endpointId, trigger, scheduledAt,
                    bounded ? scheduledRunPermits::release : () -> {});
            if (h != null) handles.add(h);  // null: vanished since the resolve, or no longer due
        }
        return handles;
    }

    /**
     * The worker of a queued run the dispatcher has just moved {@code Queued → Running} (#952): it owns
     * {@code lock} from here and runs {@code onEnd} once it has closed it, so the slot is back before the
     * permit is. A throw from the submit leaves the lock to the caller, which still holds it.
     */
    Future<RunStatus> startClaimedRun(Connector c, ConnectorEndpoint endpoint, UUID runId, RunLock lock,
                                      Runnable onEnd) {
        return vt.submit(() -> {
            try {
                return executeEndpointRun(endpoint.getId(), runId, lock, () -> runLoadedEndpoint(c, endpoint, runId, lock));
            } finally {
                onEnd.run();
            }
        });
    }

    /**
     * Takes one endpoint's lock, writes its row and submits its worker. {@code onEnd} runs exactly
     * once: at once when no worker took the run over, otherwise from the worker once it has closed the
     * lock, so the slot is back before {@code onEnd} hands its permit on.
     */
    private RunHandle startEndpointRun(UUID endpointId, RunTrigger trigger, Instant scheduledAt, Runnable onEnd) {
        boolean handedOver = false;
        try {
            StartedRun s = startRunRowInRequestContext(endpointId, trigger, scheduledAt);
            if (s == null) return null;
            if (s.lock() == null) {
                // Ended at birth (lock held elsewhere, or lock error): nothing to run.
                return new RunHandle(s.runId(), CompletableFuture.completedFuture(s.ended()));
            }
            Future<RunStatus> f;
            try {
                f = vt.submit(() -> {
                    try {
                        return executeEndpointRun(endpointId, s.runId(), s.lock());
                    } finally {
                        onEnd.run();
                    }
                });
            } catch (RuntimeException e) {
                // Executor shut down: the worker that would close the lock never starts.
                s.lock().close();
                throw e;
            }
            handedOver = true;
            return new RunHandle(s.runId(), f);
        } finally {
            if (!handedOver) onEnd.run();
        }
    }

    /** Waits for a scheduled-run permit; {@code false}, with the flag restored, when interrupted. */
    private boolean awaitScheduledRunPermit() {
        try {
            scheduledRunPermits.acquire();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Whether a scheduled fan-out of {@code connectorId} is still starting its endpoints in THIS
     * JVM — {@link ConnectorScheduler} does not submit another one on top of it.
     */
    public boolean hasPendingFanOut(UUID connectorId) {
        return pendingFanOuts.containsKey(connectorId);
    }

    /** Package-private: {@code EndpointRunConcurrencyBoundIT} holds the permits to see a fan-out wait. */
    Semaphore scheduledRunPermits() {
        return scheduledRunPermits;
    }

    /**
     * A run row just written by {@link #startRunRowInRequestContext}. Either {@code lock} is held —
     * the run owns its endpoint's {@link RunLock} and the new owner must close it — or the row was
     * written already ended and {@code ended} says how.
     */
    private record StartedRun(UUID runId, RunLock lock, RunStatus ended) {}

    /**
     * Fire-and-forget fan-out for callers that ignore the outcome (the scheduler). Submits the whole
     * cycle on the vthread executor and returns a single Future that resolves once EVERY endpoint run
     * has finished, to a coarse aggregate (the first non-Completed status, else Completed). Bounded
     * by {@code lumnik.connector.max-concurrent-scheduled-runs} (#897): endpoints wait their turn.
     */
    public Future<RunStatus> submitAsync(UUID connectorId, RunTrigger trigger) {
        Instant submittedAt = Instant.now();
        return vt.submit(() -> {
            List<RunHandle> handles;
            pendingFanOuts.merge(connectorId, 1, Integer::sum);
            try {
                handles = submitRunHandles(connectorId, trigger, submittedAt);
            } finally {
                pendingFanOuts.computeIfPresent(connectorId, (k, n) -> n == 1 ? null : n - 1);
            }
            if (Thread.currentThread().isInterrupted()) {
                // Interrupted while an endpoint waited for its permit: the rest never started, and
                // the ones that did are cancelled exactly as an interrupted wait below cancels them.
                cancelOutstanding(handles, 0);
                return new RunStatus.Failed(Instant.now(), "interrupted", false);
            }
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
     * registered in {@code liveRuns} yet (endpoint/connector still resolving, chunk loop not yet
     * entered) has nothing to flag — {@code computeIfPresent} correctly declines rather than
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
     * Async worker for one endpoint: continues the pre-created {@code runId} under the endpoint lock
     * taken before that row was written, and closes the lock when the run ends. Activates a request
     * context, sets tenant, and drives the chunk loop.
     * NEVER swallows a failure — any escaping exception finalizes the run row as {@code Failed}.
     */
    private RunStatus executeEndpointRun(UUID endpointId, UUID runId, RunLock lock) {
        return executeEndpointRun(endpointId, runId, lock, () -> runSyncEndpoint(endpointId, runId, lock));
    }

    /** {@code body} is the run itself: the sync entry that loads its entities, or a queued run's hand-over. */
    private RunStatus executeEndpointRun(UUID endpointId, UUID runId, RunLock lock, Supplier<RunStatus> body) {
        // The lock outlives the catch below: a run failing unexpectedly still owns its row while
        // it writes the Failed ending, so the reaper cannot take the row from under it.
        try (lock) {
            return executeEndpointRunLocked(endpointId, runId, body);
        }
    }

    private RunStatus executeEndpointRunLocked(UUID endpointId, UUID runId, Supplier<RunStatus> body) {
        var requestContext = Arc.container().requestContext();
        requestContext.activate();
        try {
            return body.get();
        } catch (Exception e) {
            // Two sources can reach this worker as a real Thread.interrupt(): cancelOutstanding()
            // via h.future().cancel(true), and onStop()'s vt.shutdownNow() at hub shutdown. The
            // interrupt aborts the JDBC call in flight but leaves the thread's flag set, and a flagged
            // thread would poison the finalizeRun() write below the same way, leaving the row Running
            // until the reaper sees its lock abandoned. So the flag is cleared for that write (and the
            // read that follows it) and restored right after: scoped to those calls, not swallowed
            // for the rest of the thread's life, so this holds for a pooled executor too.
            boolean interrupted = Thread.interrupted();
            LOG.errorf(e, "Connector run %s (endpoint %s) failed unexpectedly", runId, endpointId);
            Instant endedAt = Instant.now();   // one instant for the row and the returned status
            boolean ended = TenantContext.callAsSystem(() -> finalizeRun(runId, FAILED, e.getMessage(), e, endedAt));
            // Lost the race (a cancel or a normal ending committed first): report what the row says,
            // not a Failed that never happened. Read before the flag is restored, like the write above.
            ConnectorRun winner = ended ? null : rereadRun(runId);
            RunStatus outcome = winner != null
                    ? toRunStatus(winner, e.getMessage())
                    : new RunStatus.Failed(endedAt, e.getMessage(), false);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return outcome;
        } finally {
            // Catch-all drain: completeRun/failRun/cancelRun already drain on normal endings
            // (idempotent — remove()); this also covers the unexpected-exception path above, which
            // finalizes the run FAILED outside those helpers and would otherwise leak the stats map.
            stats.drain(runId);
            requestContext.deactivate();
        }
    }

    /**
     * The run row as committed, in its own transaction and past the L1 cache: a plain find inside
     * a context that already holds the row would hand back the in-memory copy, not what Postgres
     * stored (nanosecond instants it rounds, a status another ending committed).
     */
    private ConnectorRun rereadRun(UUID runId) {
        return TenantContext.callAsSystem(() -> QuarkusTransaction.requiringNew().call(() -> {
            runs.getEntityManager().clear();
            return runs.findById(runId);
        }));
    }

    /**
     * The {@link RunStatus} a run row carries — what an ending that lost the race to
     * {@link #finalizeRun} reports. {@code fallback} is the message used when the row is gone or
     * still reads Running, which only an out-of-band write could cause.
     */
    private static RunStatus toRunStatus(ConnectorRun r, String fallback) {
        if (r == null) return new RunStatus.Failed(Instant.now(), fallback, false);
        Instant at = r.getEndedAt() != null ? r.getEndedAt() : Instant.now();
        return switch (ConnectorRunStatus.fromSql(r.getStatus())) {
            case COMPLETED -> new RunStatus.Completed(at, r.getRecordsOut(), Duration.between(r.getStartedAt(), at));
            case PARTIAL   -> new RunStatus.Partial(at, r.getRecordsOut(), r.getRecordsSkipped());
            case CANCELLED -> new RunStatus.Cancelled(at, r.getErrorMessage());
            case FAILED    -> new RunStatus.Failed(at, r.getErrorMessage(), false);
            case QUEUED, RUNNING -> new RunStatus.Failed(Instant.now(), fallback, false);
        };
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
            StartedRun started = startRunRowInRequestContext(eid, trigger, null);
            RunStatus s;
            if (started == null) {
                s = new RunStatus.Failed(Instant.now(), "Endpoint not found: " + eid, false);
            } else if (started.lock() == null) {
                s = started.ended();
            } else {
                try (RunLock lock = started.lock()) {
                    s = runSyncEndpoint(eid, started.runId(), lock);
                } finally {
                    // Same catch-all as the async worker: drain even if runSyncEndpoint throws
                    // unexpectedly (idempotent on normal endings).
                    stats.drain(started.runId());
                }
            }
            if (result == null || (result instanceof RunStatus.Completed && !(s instanceof RunStatus.Completed))) {
                result = s;
            }
        }
        return result;
    }

    /**
     * Takes the endpoint's {@link RunLock}, THEN writes the {@code connector_run} row, so callers can
     * dereference the run before its chunk loop runs. Returns {@code null} if the endpoint or its
     * connector cannot be loaded. Needs an active request context on the caller.
     *
     * <p>Lock first is what lets the reaper trust a free lock: a row is only ever committed
     * {@code Running} by a session that already holds its endpoint's lock, so "Running and unlocked"
     * means its owner is gone, with no grace period to wait out. When the lock cannot be had —
     * another run holds it, or the lock query failed — the row is still written, so the refusal is
     * visible in the run history, but it is ended in the SAME transaction: it is never committed
     * {@code Running}, and the reaper never sees it.
     *
     * <p>The row is written inside a {@code REQUIRES_NEW} transaction so the Agroal interceptor
     * fires and sets the tenant GUC before any table scan. Without this, {@code findById} on an
     * RLS-enforced datasource (no active JTA tx on the caller) acquires a connection in auto-commit
     * mode where the interceptor does not fire, the GUC remains unset, and RLS returns null for
     * every row — causing the run row to never be created (finding lm-connector-run-rls-404, 422
     * follow-on).
     */
    private StartedRun startRunRowInRequestContext(UUID endpointId, RunTrigger trigger, Instant scheduledAt) {
        RunLock lock = null;
        SQLException lockError = null;
        try {
            lock = RunLock.tryAcquire(adminDs, endpointId);
        } catch (SQLException e) {
            lockError = e;
        }
        var requestContext = Arc.container().requestContext();
        boolean activatedHere = !requestContext.isActive();
        if (activatedHere) requestContext.activate();
        StartedRun started = null;
        try {
            final RunLock held = lock;
            final SQLException error = lockError;
            try {
                started = TenantContext.callAsSystem(() ->
                    QuarkusTransaction.requiringNew().call(() -> {
                        ConnectorEndpoint endpoint = endpoints.findById(endpointId);
                        if (endpoint == null) return null;
                        Connector c = connectors.findById(endpoint.getConnectorId());
                        if (c == null) return null;
                        String notDue = scheduledAt == null ? null : noLongerDue(c, endpoint, scheduledAt);
                        if (notDue != null) {
                            LOG.infof("scheduled run of endpoint %s (connector %s) not started: %s",
                                    endpoint.getName(), c.getName(), notDue);
                            return null;
                        }
                        if (held == null) return endedAtBirth(c, endpoint, trigger, error);
                        reapAbandonedRuns(endpoint.getId(), c.getTenantId());
                        return new StartedRun(startRunRow(c, endpoint, trigger).getId(), held, null);
                    })
                );
            } catch (RuntimeException e) {
                if (!isInFlightConflict(e)) throw e;
                // The index outranks our lock: a Queued row (#952) or a legacy Running one is in flight.
                LOG.infof("run of endpoint %s not started: another run is already queued or running", endpointId);
                started = bornCancelled(endpointId, trigger);
            }
            return started;
        } finally {
            // Nobody took the lock over: no row, or the transaction threw.
            if (lock != null && (started == null || started.lock() == null)) lock.close();
            if (activatedHere) requestContext.deactivate();
        }
    }

    /** V68's index refused a second Queued|Running row for the endpoint. */
    private static boolean isInFlightConflict(Throwable e) {
        return "23505".equals(SqlFailureText.sqlStateOf(e)) && SqlFailureText.causeChain(e).stream()
                .anyMatch(t -> t.getMessage() != null && t.getMessage().contains("uq_connector_run_in_flight"));
    }

    /** The run of an endpoint that already has one in flight: written already ended, in a transaction of its own. */
    private StartedRun bornCancelled(UUID endpointId, RunTrigger trigger) {
        return TenantContext.callAsSystem(() -> QuarkusTransaction.requiringNew().call(() -> {
            ConnectorEndpoint endpoint = endpoints.findById(endpointId);
            Connector c = endpoint == null ? null : connectors.findById(endpoint.getConnectorId());
            return c == null ? null : endedAtBirth(c, endpoint, trigger, null);
        }));
    }

    /**
     * Holding the endpoint's lock proves any older {@code Running} row of it is abandoned (a live run holds
     * the lock for its whole life), so it is failed here, the reaper's ending, before the new row is written:
     * V68's unique index would refuse a second in-flight row, and the door would answer 500 until the
     * reaper's next tick (#952). The reaper itself cannot do this after the lock is taken — it would see
     * the lock held and leave the row alone.
     */
    private void reapAbandonedRuns(UUID endpointId, long tenantId) {
        for (ConnectorRun stale : runs.find("endpointId = ?1 and status = ?2", endpointId,
                ConnectorRunStatus.RUNNING.sqlValue()).list()) {
            reapRun(stale.getId(), tenantId, RunReaper.REASON, Instant.now());
        }
        runs.flush();   // Hibernate runs INSERTs before UPDATEs: the old row must leave Running first
    }

    /**
     * Ends a row that never got its lock, inside the transaction that wrote it. A held lock means a
     * redundant submission (scheduler re-fire, manual+schedule overlap), which is skipped instead of
     * piling up; a lock error fails the run as retryable.
     *
     * <p>Known transient on the skip: this stamps the endpoint's {@code last_run_status} Cancelled
     * while the OTHER (live) run is still mid-flight; that run's own finalizeRun later overwrites it
     * with its real terminal status, so the endpoint self-heals — no action needed.
     */
    private StartedRun endedAtBirth(Connector c, ConnectorEndpoint endpoint, RunTrigger trigger,
                                    SQLException lockError) {
        Instant endedAt = Instant.now();
        boolean failed = lockError != null;
        String msg = failed ? "advisory lock error: " + lockError.getMessage()
                : "skipped: another run is already active for this endpoint";
        ConnectorRunStatus status = failed ? FAILED : CANCELLED;
        // Born terminal, never Running: V68's unique index counts Running, and a live run may hold this
        // endpoint, so a second Running row could not even be inserted (#952). What finalizeNeverRan did
        // after the fact is done here in one write: exempt from resolution, endpoint stamped with its run.
        ConnectorRun run = newRunRow(c, endpoint, trigger);
        run.setStatus(status.sqlValue());
        run.setStartedAt(endedAt);   // a run that never ran: a zero-length span, as the queue's endings
        run.setEndedAt(endedAt);
        run.setErrorMessage(msg);
        if (failed) run.setErrorStack(throwableToString(lockError));
        run.setCursorAfter(run.getCursorBefore());
        run.setResolutionExempt(true);
        runs.persist(run);
        runs.flush();
        ingestionMetrics.recordRunFinished(status.sqlValue());
        endpoint.setLastRunStatus(status.sqlValue());
        endpoint.setLastRunAt(endedAt);
        LOG.logf(failed ? Logger.Level.WARN : Logger.Level.INFO, "run %s %s: endpoint %s never ran — %s",
                run.getId(), status.sqlValue(), endpoint.getName(), msg);
        return new StartedRun(run.getId(), null, failed
                ? new RunStatus.Failed(endedAt, msg, true) : new RunStatus.Cancelled(endedAt, msg));
    }

    /**
     * Why a scheduled endpoint must not start after all, or {@code null} (#897). It was resolved when
     * its fan-out was submitted, and waiting for a permit can take hours: meanwhile the connector may
     * have been paused or deleted, the endpoint disabled, the run window closed, or the endpoint run
     * for this slot already — by a manual run, or by another replica's fan-out that saw no row yet.
     * Read under the endpoint's lock when it is held, so a run that ended just before still counts.
     */
    private String noLongerDue(Connector c, ConnectorEndpoint endpoint, Instant scheduledAt) {
        if (!c.isEnabled() || c.isDeleted()) return "the connector was paused or deleted while it waited for a run slot";
        if (!endpoint.isEnabled() || endpoint.isDeleted()) return "the endpoint was disabled or deleted while it waited";
        if (!RunWindow.contains(c.getRunWindow(), LocalTime.now(ZoneOffset.UTC))) return "its run window closed while it waited";
        if (runs.count("endpointId = ?1 and startedAt >= ?2", endpoint.getId(), scheduledAt) > 0) {
            return "it already ran since this slot was submitted";
        }
        return null;
    }

    /**
     * Runs ONE endpoint synchronously against a pre-created run row whose endpoint lock is NOT held
     * yet: takes the lock itself, then runs. For callers that write their own run row (tests,
     * tools). The engine's own paths go through {@link #startRunRowInRequestContext}, which takes
     * the lock before the row exists.
     */
    public RunStatus runSyncEndpoint(UUID endpointId, UUID runId) {
        return runSyncEndpoint(endpointId, runId, null);
    }

    /**
     * Runs ONE endpoint synchronously against a pre-created run row: loads endpoint + connector,
     * sets tenant, delegates the chunk loop. {@code lock} is the endpoint lock the caller already
     * holds and still owns, or {@code null} to take it here.
     *
     * <p>The initial entity lookups run inside their own {@code REQUIRES_NEW} transactions so the
     * Agroal interceptor fires and sets the tenant GUC. Without a JTA transaction the interceptor
     * does not fire; on an RLS-enforced datasource the GUC would remain unset and every row lookup
     * would return null (finding lm-connector-run-rls-404, async-404 follow-on).
     */
    private RunStatus runSyncEndpoint(UUID endpointId, UUID runId, RunLock lock) {
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
        return runLoadedEndpoint(c, endpoint, runId, lock);
    }

    /**
     * The run itself, from entities already loaded: {@link #runSyncEndpoint} loads them, and a queued run's
     * dispatcher hands over the very ones it hashed (#952, D10), so a re-apply after the commit cannot change them.
     */
    private RunStatus runLoadedEndpoint(Connector c, ConnectorEndpoint endpoint, UUID runId, RunLock lock) {
        // Everything this run does happens under its tenant AND under its identity in the log.
        // TenantScope sets both ThreadLocals — the Hibernate filter's and the RLS GUC's — plus the
        // tenantId MDC, and RESTORES what was there; the hand-rolled pair this replaces removed
        // instead, which lost a synchronous caller's tenant on the way out. runId rides alongside so
        // every line raised mid-run says which run raised it: the sixteen reader/writer WARNs named
        // neither the run nor the tenant, and %X{tenantId} — already in the console format — printed
        // empty for everything a run wrote, because no one on this thread ever filled it.
        MDC.put(MDC_RUN_ID, runId.toString());
        try {
            return TenantScope.get(c.getTenantId(), () -> doRunEndpoint(c, endpoint, runId, lock));
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
                                              RunBound bound, RunScope scope) {
        ConnectorRun run = QuarkusTransaction.requiringNew().call(() -> runs.findById(runId));
        if (run == null) return new ChunkResult.Failure("Run not found: " + runId, null, false);
        return chunkExecutor.executeChunk(run, type, endpoint, cursor, bound, scope);
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
                                ConnectorEndpoint endpoint, ConnectorCursor cursor, RunScope scope) {
        try {
            return chunkExecutor.runStart(run, c, type, endpoint, cursor, scope);
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
        ConnectorRun run = newRunRow(c, endpoint, trigger);
        runs.persist(run);
        // #748: the row's DB-clock resolution_fence_at is stamped by this INSERT, and the chunk thread is
        // submitted only after this transaction commits (submitRunHandles), so the fence precedes every
        // _ingested_at the run writes. ResolutionRunFenceEngineIT pins that ordering end to end.
        runs.flush();
        return run;
    }

    /** A run row as the engine writes it at birth, not yet persisted: Running, started now, cursor as it stands. */
    private ConnectorRun newRunRow(Connector c, ConnectorEndpoint endpoint, RunTrigger trigger) {
        ConnectorRun run = new ConnectorRun();
        run.setTenantId(c.getTenantId());
        run.setConnectorId(c.getId());
        run.setEndpointId(endpoint.getId());
        run.setStatus(ConnectorRunStatus.RUNNING.sqlValue());
        run.setSyncMode(syncModeName(c.getSyncMode()));
        run.setStartedAt(Instant.now());
        run.setTriggeredBy(triggerName(trigger));
        run.setTriggeredMeta(mapper.valueToTree(trigger));
        ConnectorCursorEntity cursorEnt = cursors.findByEndpoint(endpoint.getId());
        run.setCursorBefore(cursorEnt != null ? cursorEnt.getCursorValue() : null);
        return run;
    }

    /**
     * The single place a run row reaches a terminal status — every ending (Completed, Partial,
     * Failed, Cancelled, and a reaped run) funnels through here. The endpoint's
     * {@code last_run_status} and {@code last_run_at} are stamped with the SAME string and the SAME
     * instant in the SAME transaction, so run row and endpoint can never disagree.
     *
     * <p>A terminal status is final. The row is read under a row lock, so concurrent endings are
     * serialized. If the row has already left {@code Running} — the reaper failed it, or another
     * ending won the race — this logs and changes nothing, and returns
     * {@code false} so the caller does not announce an ending that did not happen.
     *
     * <p>{@code endedAt} is the caller's clock: the reaper hands in the instant it decided the run
     * was dead, every other ending passes {@code Instant.now()}.
     */
    @Transactional
    public boolean finalizeRun(UUID runId, ConnectorRunStatus status, String errorMessage,
                               Throwable errorStack, Instant endedAt) {
        if (!status.isTerminal()) {
            throw new IllegalArgumentException("finalizeRun needs a terminal status, got " + status);
        }
        // Read under a row lock: two endings racing for one row (the reaper and the run's own
        // finish, a cancel and a failure) would otherwise both read Running and both write, and the
        // last commit would win. SELECT ... FOR UPDATE makes the second wait for the first to commit,
        // then read its terminal status and back off, so exactly one ending takes effect.
        ConnectorRun r = runs.findById(runId, LockModeType.PESSIMISTIC_WRITE);
        if (r == null) return false;
        if (!ConnectorRunStatus.isRunning(r.getStatus())) {
            LOG.warnf("run %s already ended %s — not moving it to %s", runId, r.getStatus(), status.sqlValue());
            return false;
        }
        r.setStatus(status.sqlValue());
        ingestionMetrics.recordRunFinished(status.sqlValue());
        r.setEndedAt(endedAt);
        if (errorMessage != null) r.setErrorMessage(errorMessage);
        if (errorStack != null)   r.setErrorStack(throwableToString(errorStack));
        ConnectorCursorEntity cursorEnt = r.getEndpointId() != null
                ? cursors.findByEndpoint(r.getEndpointId()) : null;
        if (cursorEnt != null) r.setCursorAfter(cursorEnt.getCursorValue());
        ConnectorEndpoint endpoint = r.getEndpointId() != null ? endpoints.findById(r.getEndpointId()) : null;
        if (endpoint != null) {
            endpoint.setLastRunStatus(status.sqlValue());
            endpoint.setLastRunAt(endedAt);
        }
        String reason = errorMessage != null && !errorMessage.isBlank() ? " — " + errorMessage : "";
        Object endpointLabel = endpoint != null ? endpoint.getName() : r.getEndpointId();   // the id when the row is gone
        // Partial is a WARN with Failed: both are endings the operator has to notice. An INFO line
        // is what made the two silences this arc closes invisible in the first place.
        LOG.logf(status == FAILED || status == PARTIAL
                        ? Logger.Level.WARN : Logger.Level.INFO,
                "run %s %s: endpoint %s, in=%d out=%d skipped=%d cell_errors=%d chunks=%d, %d ms%s",
                runId, status.sqlValue(), endpointLabel, r.getRecordsIn(), r.getRecordsOut(),
                r.getRecordsSkipped(), r.getCellErrors(), r.getChunkCount(),
                Duration.between(r.getStartedAt(), endedAt).toMillis(), reason);
        return true;
    }

    /**
     * The ending of a run that acquired no endpoint lock (skipped for a held one, or the attempt failed) and
     * so executed nothing (#748): the row is flagged {@code resolution_exempt} under the same row lock the
     * ending takes, so the resolution ledger does not wait for a pass over rows that were never written.
     */
    @Transactional
    public boolean finalizeNeverRan(UUID runId, ConnectorRunStatus status, String errorMessage,
                                    Throwable errorStack, Instant endedAt) {
        ConnectorRun r = runs.findById(runId, LockModeType.PESSIMISTIC_WRITE);
        if (r != null && ConnectorRunStatus.isRunning(r.getStatus())) r.setResolutionExempt(true);
        return finalizeRun(runId, status, errorMessage, errorStack, endedAt);
    }

    /**
     * Fails a run the reaper found with no live owner. Same ending as any other failure: the row
     * and the endpoint through {@link #finalizeRun}, then the {@code connector.run.failed} event.
     * Returns {@code false} when the row had already ended, and then emits nothing.
     *
     * <p>{@code tenantId} is the run's own: the outbox drops an event published with no tenant
     * bound, and the reaper sweeps all tenants from a thread that has none.
     */
    public boolean reapRun(UUID runId, long tenantId, String reason, Instant endedAt) {
        boolean ended = TenantContext.callAsSystem(() -> finalizeRun(runId, FAILED, reason, null, endedAt));
        if (!ended) return false;
        stats.drain(runId);
        TenantScope.run(tenantId, () -> events.emit("connector.run.failed", Map.of(
                RUN_ID,          runId.toString(),
                "error_message", reason,
                "retryable",     true)));
        return true;
    }

    /**
     * {@code held} is the endpoint lock the caller took before writing the row, and still owns. When
     * it is {@code null} — a row written by someone else ({@link #runSyncEndpoint(UUID, UUID)}) — the lock is taken here, and held for the run.
     */
    private RunStatus doRunEndpoint(Connector c, ConnectorEndpoint endpoint, UUID runId, RunLock held) {
        ConnectorType type;
        try {
            type = registry.find(c.getTypeId());
        } catch (Exception e) {
            TenantContext.callAsSystem(() -> finalizeRun(runId, FAILED, e.getMessage(), e, Instant.now()));
            return new RunStatus.Failed(Instant.now(), e.getMessage(), false);
        }
        if (held != null) return runChunks(c, endpoint, runId, type);

        // Per-ENDPOINT advisory lock: serialize runs of the SAME endpoint across the whole
        // cluster (each endpoint owns one cursor row). Distinct endpoints of one connector run
        // in parallel (independent cursors, no shared state). Held on a dedicated connection for
        // the run's lifetime; chunk transactions use their own pooled connections. A pod crash
        // ends the session and Postgres releases the lock automatically. Non-blocking: a
        // redundant submission skips instead of piling up.
        RunLock lock;
        try {
            lock = RunLock.tryAcquire(adminDs, endpoint.getId());
        } catch (SQLException e) {
            TenantContext.callAsSystem(() -> finalizeNeverRan(runId, FAILED, "advisory lock error: " + e.getMessage(), e, Instant.now()));
            return new RunStatus.Failed(Instant.now(), "advisory lock error: " + e.getMessage(), true);
        }
        if (lock == null) {
            // Same known transient as endedAtBirth: the endpoint reads Cancelled until the live run ends.
            TenantContext.callAsSystem(() -> finalizeNeverRan(runId, CANCELLED, "skipped: another run is already active for this endpoint", null, Instant.now()));
            // defensive: nothing accumulated yet on this path, but keep the drain symmetric with complete/fail/cancel
            stats.drain(runId);
            return new RunStatus.Cancelled(Instant.now(),
                    "skipped: another run is already active for this endpoint");
        }

        try (lock) {
            // This row was committed Running before its lock was taken, so the reaper could have
            // found it unlocked and failed it. The reaper holds the lock while it does, so once WE
            // hold it that verdict is settled: a row no longer Running was reaped, and must not run.
            // A row that is gone is not this check's business: runChunks refuses it by name.
            ConnectorRun current = rereadRun(runId);
            if (current != null && !ConnectorRunStatus.isRunning(current.getStatus())) {
                stats.drain(runId);
                return toRunStatus(current, "run " + runId + " was reaped before it started");
            }
            return runChunks(c, endpoint, runId, type);
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

        // Created BEFORE runStart and passed in: ChunkExecutor rebuilds the ConnectorContext for every chunk (and once
        // for runStart), so a scope built there would be a new one each time. Closed in the finally below (#898).
        OwnedRunScope scope = new OwnedRunScope(runId);
        Throwable escaping = null;
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
                        runStartOf(run, c, type, endpoint, startedFrom, scope));
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
                        executeChunkManaged(runId, type, endpoint, before, bound, scope));

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
        } catch (Throwable t) {
            escaping = t;
            throw t;
        } finally {
            releaseRunScope(scope, runId, escaping);
        }
    }

    /**
     * Closes the run's scope and forgets the run. On the normal endings the terminal status is persisted by now (the
     * return value is computed before the finally). When something unexpected escapes instead, this close comes first:
     * on the worker paths (async and queued, through {@code executeEndpointRun}) a {@code RuntimeException} is then
     * finalized {@code Failed} by the net in {@code executeEndpointRunLocked}; the synchronous entries ({@code runSync},
     * {@code runSyncEndpoint}) have no such net, and an {@code Error} is caught by none (the net catches
     * {@code Exception}), so there the row stays {@code Running} until the reaper.
     *
     * <p>A failure of a resource's close is logged inside the scope; only a {@code VirtualMachineError} gets out of it. When something is already escaping the run, that error rides along as suppressed on
     * it instead of replacing it (a {@code finally} that throws would lose the failure that ended the run). The live-run
     * entry goes either way: a leaked one would make a finished run look cancellable.
     */
    private void releaseRunScope(OwnedRunScope scope, UUID runId, Throwable escaping) {
        try {
            scope.close();
        } catch (Error closeError) {
            if (escaping == null) throw closeError;
            escaping.addSuppressed(closeError);
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
     * ever produced it. It is one of the five values V53 allows in {@code connector_run.status}.
     *
     * <p>Partial means ONE thing: the run stopped below its target — a shortfall in POSITION. A
     * shortfall in DELIVERY (rows read and dropped) is not this; {@code records_skipped}, the
     * dead-letter list and OTK Law 1 already say it in full, and a status that answered both
     * questions would end up lying to one of them.
     */
    private RunStatus partialRun(Connector c, ConnectorEndpoint endpoint, UUID runId,
                                 ConnectorRun run, String detail) {
        ConnectorRun ended = finishRun(c, endpoint, runId, run, PARTIAL, detail, "connector.run.partial");
        return ended != null ? toRunStatus(ended, detail)
                : new RunStatus.Partial(Instant.now(), run.getRecordsOut(), run.getRecordsSkipped());
    }

    private RunStatus cancelRun(UUID runId) {
        Instant endedAt = Instant.now();
        boolean ended = TenantContext.callAsSystem(() -> finalizeRun(runId, CANCELLED, "cancelled by user", null, endedAt));
        stats.drain(runId); // drain-and-discard: prevent the in-JVM map leaking on a non-complete ending
        // An ending that lost the race to another announces nothing: the winner already emitted the
        // event that matches the row. A row that is gone had no winner, so this ending still speaks.
        ConnectorRun winner = ended ? null : rereadRun(runId);
        if (winner != null) return toRunStatus(winner, "cancelled");
        events.emit("connector.run.cancelled", Map.of(RUN_ID, runId.toString()));
        return new RunStatus.Cancelled(endedAt, "cancelled");
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
        ConnectorRun ended = finishRun(c, endpoint, runId, run, COMPLETED, null, "connector.run.completed");
        return ended != null ? toRunStatus(ended, null)
                : new RunStatus.Completed(Instant.now(), run.getRecordsOut(),
                        Duration.between(run.getStartedAt(), Instant.now()));
    }

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
     *
     * <p>Returns the row as committed (null when it is gone), so the caller reports the status that
     * actually stuck: its own when it won, the winner's when another ending committed first.
     */
    private ConnectorRun finishRun(Connector c, ConnectorEndpoint endpoint, UUID runId, ConnectorRun run,
                            ConnectorRunStatus status, String detail, String topic) {
        boolean ended = TenantContext.callAsSystem(() -> finalizeRun(runId, status, detail, null, Instant.now()));
        // A real re-read, so the span below is the one a reader of the stored columns computes.
        ConnectorRun refreshed = rereadRun(runId);
        long recordsOut = refreshed != null ? refreshed.getRecordsOut() : run.getRecordsOut();
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
        Map<String, Map<String, Long>> tables = stats.drain(runId);
        payload.put("tables", tables);
        // Only the endings that have something to explain carry it, so a `completed` payload keeps
        // exactly the keys its subscribers already read.
        if (detail != null) payload.put("error_message", detail);
        // Lost the race: the row carries another ending, whose own event is already out. The tables
        // were still written, so the stats drain above and the refresh below happen regardless.
        // A row that is gone had no winner, so this ending still announces itself.
        if (ended || refreshed == null) events.emit(topic, payload);
        // Stamp the event with the run's ACTUAL start (NOT NULL, set in startRunRow before any chunk
        // ran). This is the start of the refresher's schema-change look-back window — an Instant.now()
        // fallback here would start the window at finalization time and could miss a table created
        // earlier in the run, skipping the SchemaCard refresh.
        // Only the ending that won vouches for the tables: the one that beat it (the reaper) drained
        // them first, and an empty list would skip every resolution source of a run that wrote (#904).
        Set<String> tablesWritten = ended ? Set.copyOf(tables.keySet()) : null;
        runCompleted.fireAsync(new ConnectorRunCompleted(
                c.getId(), c.getTenantId(), runId, startedAt, tablesWritten));
        return refreshed;
    }

    private RunStatus failRun(UUID runId, ChunkResult.Failure f) {
        Instant endedAt = Instant.now();
        boolean ended = TenantContext.callAsSystem(() -> finalizeRun(runId, FAILED, f.message(), f.cause(), endedAt));
        stats.drain(runId); // drain-and-discard: prevent the in-JVM map leaking on a non-complete ending
        ConnectorRun winner = ended ? null : rereadRun(runId);   // null: won, or the row is gone
        if (winner != null) return toRunStatus(winner, f.message());
        events.emit("connector.run.failed", Map.of(
                RUN_ID,        runId.toString(),
                "error_message", f.message() == null ? "" : f.message(),
                "retryable",     f.retryable()));
        return new RunStatus.Failed(endedAt, f.message(), f.retryable());
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
