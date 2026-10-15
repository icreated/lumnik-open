package io.lumnik.hub.search.projection;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.search.ProjectionSink;
import io.lumnik.hub.run.RunLock;
import io.lumnik.hub.search.ProjectionSinkGate;
import io.quarkus.agroal.DataSource;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Heals a {@code core.search_projection_state} row left {@code building} by a process that is gone
 * (design doc D-reaper, §2): the exact {@code RunReaper}/{@code ExportRunReaper} shape applied to
 * this table. A rebuild's {@link RunLock} is taken BEFORE the row is written or transitioned (§5b
 * step 0), so a {@code building} row is never visible with a free lock unless its owner died —
 * acquiring the lock here proves nobody live still holds it.
 *
 * <p>Reconciliation ({@link #reconcile}) is four branches keyed on {@code active_run_phase}/{@code
 * cancel_requested_run_id} (design doc §3.4, #703) — legacy/building-cancelling/building/publishing —
 * each its own conditional {@code UPDATE ... WHERE active_run_phase = ? AND active_run_id = ?},
 * {@code ExportRunLedger.fail}'s own pattern extended to the run-tracking columns V58 added: a live
 * rebuild that publishes and releases its lock in the race window between this reaper's SELECT and
 * its own {@code tryAcquire} must not be downgraded — zero rows updated is a normal, silent outcome
 * there, not an error.
 *
 * <p>No {@code @Scheduled} on this class itself — {@link #reap()} is driven at boot ({@link
 * #onStart}) and, every minute after, by {@link SearchProjectionScheduler}'s own tick, exactly like
 * {@code RunReaper}/{@code ExportRunReaper} are driven by {@code ConnectorScheduler}/{@code
 * ExportScheduler}.
 */
@ApplicationScoped
public class SearchProjectionRunReaper {

    private static final Logger LOG = Logger.getLogger(SearchProjectionRunReaper.class);

    static final String REASON = "reaped: the hub process building it restarted or died while it was in flight";

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject SearchProjectionStateStore states;
    @Inject ProjectionSinkGate sinkGate;
    @Inject SearchProjectionMetrics metrics;

    /**
     * Test-only pause point, the same idiom as {@code SearchProjectionWorker.beforeWatermarkRead}/
     * {@code afterMarkBatchFetch}: fires once per candidate row, right after this reaper's own {@link
     * SearchProjectionStateStore#findBuilding()} SELECT has already read it as {@code building}, but
     * before this reaper's own {@link RunLock#tryAcquire} for that same key. {@code
     * SearchProjectionRunReaperIT} uses it to land a "worker" that releases its lock and ends the run
     * at the exact instant spec test 23 needs — proving the reaper's own conditional branch UPDATE
     * ({@link SearchProjectionStateStore#degradeIfStillBuilding} for a legacy row, {@link
     * SearchProjectionStateStore#reapBuilding}/{@link SearchProjectionStateStore#reapCancelling}/
     * {@link SearchProjectionStateStore#reapPublishing} otherwise) matches zero rows and never
     * downgrades a rebuild that finished in that race window. Defaults to a no-op in production;
     * nothing else ever assigns it.
     */
    Runnable beforeTryAcquire = () -> {};

    /** After Flyway (@Priority(2)) — the state table must exist. */
    void onStart(@Observes @Priority(6) StartupEvent ev) {
        reap();
    }

    public int reap() {
        int reaped = 0;
        for (SearchProjectionState row : states.findBuilding()) {
            try {
                if (reapIfAbandoned(row)) reaped++;
            } catch (Exception e) {
                // One bad row must not stop the others; the next tick retries it.
                LOG.errorf(e, "SearchProjectionRunReaper could not check (%d, %s) — stays building until next tick",
                    row.tenantId(), row.searchIndex());
            }
        }
        if (reaped > 0) LOG.warnf("SearchProjectionRunReaper: reaped %d stuck rebuild(s)", reaped);
        return reaped;
    }

    private boolean reapIfAbandoned(SearchProjectionState row) throws SQLException {
        UUID key = SearchProjectionLockKey.of(row.tenantId(), row.searchIndex());
        beforeTryAcquire.run();
        return RunLock.runIfAbandoned(adminDs, key, () -> reconcile(row.tenantId(), row.searchIndex()));
    }

    /**
     * Four branches, keyed on {@code active_run_phase} and whether a cancel was requested for the
     * exact run this row still names (design doc §3.4):
     * <ul>
     *   <li>{@code null} phase — branch 0, a legacy row (pre-V58, or written by an old worker
     *       mid-rolling-deployment): no run to attribute a duration to, {@link
     *       SearchProjectionStateStore#degradeIfStillBuilding} unchanged, nothing more.</li>
     *   <li>{@code building} + a matching cancel request — branch 1: a best-effort {@link
     *       #maybeAbandon}, then {@link SearchProjectionStateStore#reapCancelling}.</li>
     *   <li>{@code building}, no cancellation — branch 2: {@link
     *       SearchProjectionStateStore#reapBuilding}, never abandoning (matches shipped #671
     *       posture).</li>
     *   <li>{@code publishing} — branch 3: {@link SearchProjectionStateStore#reapPublishing}, never
     *       abandoning — {@code sink.publish} is documented atomic but whether it committed
     *       provider-side before the crash is unknowable from here.</li>
     * </ul>
     */
    private boolean reconcile(long tenantId, String searchIndex) {
        // Re-read under the lock: findBuilding()'s own snapshot is taken BEFORE the lock is
        // acquired, so the run it named can finish cleanly — even publish a fresh live
        // generation — in the window before this reaper gets the lock. Acting on that stale
        // snapshot let maybeAbandon fire against a generation that had since gone live (code
        // review finding); a read taken now, while nobody else can be live against this key, is
        // the only one safe to branch and abandon on.
        Optional<SearchProjectionState> current = states.find(tenantId, searchIndex);
        if (current.isEmpty() || !"building".equals(current.get().status())) {
            return false; // already resolved, or the row itself is gone — nothing to reap
        }
        SearchProjectionState row = current.get();

        if (row.activeRunPhase() == null) {
            // Branch 0: a legacy row (pre-V58, or written by an old worker mid-rolling-deployment) — no
            // run to attribute, exactly today's shipped degradeIfStillBuilding and nothing more.
            return states.degradeIfStillBuilding(row.tenantId(), row.searchIndex(), REASON);
        }

        boolean cancelling = row.cancelRequestedRunId() != null
            && row.cancelRequestedRunId().equals(row.activeRunId());

        if ("building".equals(row.activeRunPhase()) && cancelling) {
            maybeAbandon(row);
            boolean hadPriorLiveGeneration = row.liveGeneration() != null;
            boolean reaped = states.reapCancelling(row.tenantId(), row.searchIndex(), row.activeRunId(),
                hadPriorLiveGeneration);
            recordDuration(row, "cancelled", reaped);
            return reaped;
        }
        if ("building".equals(row.activeRunPhase())) {
            boolean reaped = states.reapBuilding(row.tenantId(), row.searchIndex(), row.activeRunId(), REASON);
            recordDuration(row, "failed", reaped);
            return reaped;
        }
        // "publishing" — never abandons; sink.publish is documented atomic but whether it committed
        // provider-side before the crash is unknowable from here.
        boolean reaped = states.reapPublishing(row.tenantId(), row.searchIndex(), row.activeRunId(), REASON);
        recordDuration(row, "failed", reaped);
        return reaped;
    }

    /** Branch 1's own best-effort abandon: only when active_generation is known AND the currently
     *  resolved provider is the same one that produced it — a reinstall/reconfiguration around the
     *  crash means the token means nothing to whatever ProjectionSink is resolved now (design doc §3.4). */
    private void maybeAbandon(SearchProjectionState row) {
        if (row.activeGeneration() == null) return;
        Optional<ProjectionSink> resolved = sinkGate.resolve();
        if (resolved.isEmpty() || !resolved.get().name().equals(row.activeProviderName())) return;
        try {
            resolved.get().abandon(row.tenantId(), row.searchIndex(), row.activeGeneration());
        } catch (RuntimeException e) {
            LOG.warnf(e, "SearchProjectionRunReaper: best-effort abandon failed for (%d, %s) — the next "
                + "beginGeneration for this pair reclaims its own staging slot regardless",
                row.tenantId(), row.searchIndex());
        }
    }

    private void recordDuration(SearchProjectionState row, String outcome, boolean reaped) {
        if (!reaped || row.activeRunStartedAt() == null) return; // zero rows: a live run finished in the race window
        metrics.recordRebuildTerminal(outcome, Duration.between(row.activeRunStartedAt(), Instant.now()));
    }
}
