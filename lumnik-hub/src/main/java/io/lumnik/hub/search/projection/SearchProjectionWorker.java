package io.lumnik.hub.search.projection;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.extraction.spi.search.ProjectionDocument;
import io.lumnik.extraction.spi.search.ProjectionSink;
import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.entity.EntityViewCompiler;
import io.lumnik.hub.scheduler.TenantScope;
import io.lumnik.hub.search.ProjectionSinkGate;
import io.lumnik.hub.search.SearchIndexManifest;
import io.lumnik.hub.search.SearchIndexManifestParser;
import io.lumnik.hub.search.mark.MarkKind;
import io.lumnik.hub.search.mark.MarkRow;
import io.lumnik.hub.search.mark.SearchProjectionMarkStore;
import io.lumnik.hub.sql.SqlIdentifiers;
import io.lumnik.hub.sql.TenantRlsScope;
import io.lumnik.hub.run.RunLock;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.QuarkusTransactionException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.postgresql.PGStatement;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/**
 * Discovery (design doc §5a): once a minute, for every tenant with at least one valid SearchIndex,
 * dispatch each (tenant, index) pair to a full rebuild or incremental catch-up, or leave it alone.
 * Reaping and purge-drain run first (wired in Task 15's scheduler); this class's own entry point is
 * {@link #discoveryTick()}.
 *
 * <p>Scoped to the dispatch decision alone (spec tests 1, 8, 9, 10): no provider resolved -> inert
 * (the issue's own acceptance); no state row, or the row's {@code live_generation IS NULL} (including
 * a degraded row from a failed FIRST attempt, spec test 10), or a provider mismatch -> full rebuild
 * regardless of position (spec test 8); otherwise a ready pair with more pending marks than {@link
 * #rebuildBacklogThreshold} -> full rebuild (#894); a pending mark exists for the pair -> catch-up; none pending -> nothing this
 * tick. {@link #catchUp} is Task 13's full §5c implementation; {@link #rebuild} is Task 12's full §5b
 * implementation; {@link #purgeDrain} is Task 14's full §5d implementation.
 *
 * <p><b>#712:</b> dispatch no longer compares the state row's {@code position} against the mark
 * table's head id to decide catch-up vs. "nothing this tick" — a lower-id mark whose producing
 * transaction commits after a higher-id one already advanced {@code position} past it would then be
 * silently skipped forever. A pending mark's mere presence (§5a step 4, {@link
 * SearchProjectionMarkStore#pendingMarkPairsAdmin}) is what triggers catch-up now; {@code position}
 * is a monotone diagnostic counter only.
 */
@ApplicationScoped
public class SearchProjectionWorker {

    private static final Logger LOG = Logger.getLogger(SearchProjectionWorker.class);

    /** {@code EntitySnapshotStreamer.FETCH_SIZE}'s own value — the server-side cursor's fetch size,
     *  reused here as the sink's own upsert batch size (design doc §5b step 4). */
    private static final int SCAN_BATCH_SIZE = 500;

    private final ProjectionSinkGate gate;
    private final SearchProjectionMarkStore markStore;
    private final SearchProjectionStateStore stateStore;

    /** #730. Package-private and null in a worker built outside CDI (every unit test here): a null
     *  advancer means "freshness unknown" — a null base at publish, no advance — never a failure. */
    @Inject SearchProjectionAsOfAdvancer advancer;

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject @DataSource("readonly") AgroalDataSource readonlyDs;
    @Inject EntityRegistry registry;
    @Inject SearchProjectionMetrics metrics;

    @ConfigProperty(name = "lumnik.search.projection.rebuild-scan-timeout-seconds", defaultValue = "1800")
    int rebuildScanTimeoutSeconds;

    /** #754: the tick's global budget for EXTRA catch-up batches (the first attempt per pair is never gated).
     *  It gates the start of a batch and never interrupts one. {@code 0s} is the historical one-attempt-per-pair
     *  behaviour, and the initial value for a worker built outside CDI. Validated {@code >= 0} in {@link #initExecutor}. */
    @ConfigProperty(name = "lumnik.search.projection.catchup-budget", defaultValue = "30s")
    Duration catchupBudget = Duration.ZERO;

    /** #894: a pair with more than this many undrained marks is rebuilt instead of caught up — a batch reads the
     *  entity's view at about the cost of reading it whole, so K marks cost K/500 view reads where a rebuild costs
     *  one plus a write of every document (crossover measured at 1,600–6,800 marks). {@code 0} disables it, and is
     *  the initial value for a worker built outside CDI. Validated {@code >= 0} in {@link #initExecutor}. */
    @ConfigProperty(name = "lumnik.search.projection.rebuild-backlog-threshold", defaultValue = "5000")
    int rebuildBacklogThreshold;

    /** #937: in the per-mark fallback, a mark the provider refuses stays and the marks behind it still apply; after this
     *  many failures in a row the pair stops for the tick, so an outage costs this many provider calls per pair, not one
     *  per mark. {@code 1} is the historical stop-at-the-first-failure. Validated {@code >= 1} in {@link #initExecutor}. */
    @ConfigProperty(name = "lumnik.search.projection.catchup-consecutive-failure-limit", defaultValue = "3")
    int catchupConsecutiveFailureLimit = 3;

    /** Monotone clock for {@link #catchupBudget}; package-private so a test drives it. */
    LongSupplier nanoClock = System::nanoTime;

    /** #703 finding: bounds how many on-demand ({@link #triggerRebuild}) rebuilds may run at once ON
     *  THIS HUB INSTANCE — each holds a pooled {@code admin} connection (the RunLock) plus a {@code
     *  readonly} one for the whole scan, up to {@link #rebuildScanTimeoutSeconds}; unbounded, enough
     *  simultaneous triggers (e.g. a script looping over every index after a provider switch) exhaust
     *  both shared pools and starve unrelated ingestion/Ask-to-SQL traffic. Never consulted by the
     *  scheduler's own {@link #rebuild}/{@link #catchUp}/{@link #purgeDrain} — those are already
     *  bounded by the scheduler's own tick cadence, one pair at a time.
     *
     *  <p>Not deployment-wide (code review finding): {@link #rebuildPermits} is a plain in-process
     *  {@code Semaphore}, so with {@code hub.replicas} &gt; 1 the real bound on concurrent on-demand
     *  rebuilds is N replicas &times; this value, not this value alone — an operator sizing pool
     *  budget around a single deployment-wide cap should size it per replica instead. */
    @ConfigProperty(name = "lumnik.search.projection.max-concurrent-rebuilds", defaultValue = "3")
    int maxConcurrentRebuilds;

    /** Package-private and mutable, not {@code final} — a worker constructed outside CDI (every unit
     *  test in this package) sets it directly, the same idiom as {@link #rebuildScanTimeoutSeconds}. */
    Semaphore rebuildPermits;

    /**
     * The full-rebuild scan step (§5b step 4), package-private and mutable so
     * {@code SearchProjectionWorkerRebuildTest} (Step 2) can substitute a fake that drives
     * {@code sink.upsert} without a database — the real cursor/transaction mechanics are exercised
     * instead by the {@code @QuarkusTest} IT ({@code SearchProjectionFullRebuildIT}). Defaults to the
     * real JDBC-backed implementation.
     */
    RowScanner rowScanner = this::scanAndUpsert;

    /**
     * Test-only pause points bracketing the watermark read (§5b step 1), so {@code
     * SearchProjectionFullRebuildIT} can land a concurrent SearchIndex re-apply at each of the two
     * racing instants spec test 26 distinguishes: {@link #beforeWatermarkRead} fires before the
     * watermark is read at all (sub-case A — the re-apply's own mark ends up INCLUDED in the
     * watermark), {@link #afterWatermarkRead} fires after the watermark is captured but before the
     * declaration re-read (sub-case B — the re-apply's own mark is NOT included, but the fresh
     * declaration read still observes it). Both default to no-ops in production; nothing else ever
     * assigns them.
     */
    Runnable beforeWatermarkRead = () -> {};
    Runnable afterWatermarkRead = () -> {};

    /**
     * Test-only pause point, same idiom as {@link #beforeWatermarkRead}/{@link #afterWatermarkRead}:
     * fires in {@link #runCatchUp} right after the mark batch is fetched (the ids about to be applied
     * and deleted are already in hand) but before that batch is applied and its marks deleted by
     * those exact ids. {@code SearchProjectionConcurrentMarkIT} uses it to land a concurrent re-mark
     * (Task 3's {@code DO UPDATE}) of the same canonical key, synchronously and deterministically on
     * this same thread, at the one instant that proves {@link SearchProjectionMarkStore#deleteByExactIds}
     * then matches zero rows for that key — the reissued mark is never silently dropped, only picked
     * up whole by the next tick. Defaults to a no-op in production; nothing else ever assigns it.
     */
    Runnable afterMarkBatchFetch = () -> {};

    /**
     * Test-only pause point, same idiom as {@link #afterMarkBatchFetch}: fires in {@link #purgeDrain}
     * right after one purge row's destructive actions commit but before the immediately-following
     * re-check for a concurrent reapply, given that row's own (tenantId, name) — {@code purgeDrain}
     * drains the whole deployment's backlog, so a test needs this to react only to the specific pair
     * it cares about, not every row this tick happens to process first. A test uses it to land a
     * reapply (a fresh valid declaration) synchronously on this same thread, at the one instant that
     * proves the re-check catches it and drives a fresh {@link #rebuild} rather than leaving the
     * reapplied index empty until the next scheduler tick. Defaults to a no-op in production; nothing
     * else ever assigns it.
     */
    BiConsumer<Long, String> afterPurgeAction = (tenantId, name) -> {};

    @Inject
    public SearchProjectionWorker(ProjectionSinkGate gate, SearchProjectionMarkStore markStore,
                                  SearchProjectionStateStore stateStore) {
        this.gate = gate;
        this.markStore = markStore;
        this.stateStore = stateStore;
    }

    /** {@link #triggerRebuild}'s own background runner — {@code ExportRunner}'s exact idiom (one
     *  virtual thread per submitted run, no bound). */
    private ExecutorService rebuildExecutor;

    @PostConstruct
    void initExecutor() {
        if (catchupBudget.isNegative()) {
            throw new IllegalStateException("lumnik.search.projection.catchup-budget must be zero or positive, was " + catchupBudget);
        }
        if (catchupConsecutiveFailureLimit < 1) {
            throw new IllegalStateException("lumnik.search.projection.catchup-consecutive-failure-limit must be at least 1, was "
                + catchupConsecutiveFailureLimit);
        }
        if (rebuildBacklogThreshold < 0) {
            throw new IllegalStateException("lumnik.search.projection.rebuild-backlog-threshold must be zero or positive, was "
                + rebuildBacklogThreshold);
        }
        rebuildExecutor = Executors.newVirtualThreadPerTaskExecutor();
        rebuildPermits = new Semaphore(maxConcurrentRebuilds);
    }

    @PreDestroy
    void shutdownExecutor() {
        rebuildExecutor.shutdownNow();
    }

    private Instant captureBaseAsOf() {
        return advancer == null ? null : advancer.safeBound();
    }

    public void discoveryTick() {
        Optional<ProjectionSink> sink = gate.resolve();
        if (sink.isEmpty()) return; // inert without a provider — issue's own acceptance

        // Both fetched once for the whole tick, not once per (tenant, index) pair: the same N+1 shape
        // already fixed one level up for tenant/index discovery itself (code review, #671).
        Map<Long, Map<String, SearchProjectionState>> statesByTenant = stateStore.findAllAdmin();
        Map<Long, Set<String>> pendingMarksByTenant = markStore.pendingMarkPairsAdmin();

        long tickStartNanos = nanoClock.getAsLong();
        // #754: pairs whose first attempt applied a batch — they queue for extra rounds after the whole historical pass.
        Deque<ExtraCatchUp> rotation = new ArrayDeque<>();

        for (Map.Entry<Long, Map<String, List<String>>> t : markStore.validIndexNamesByEntityAndTenantAdmin().entrySet()) {
            long tenantId = t.getKey();
            Map<String, SearchProjectionState> states = statesByTenant.getOrDefault(tenantId, Map.of());
            Set<String> pendingMarks = pendingMarksByTenant.getOrDefault(tenantId, Set.of());
            for (List<String> indexNames : t.getValue().values()) {
                for (String index : indexNames) {
                    // Per-pair fault isolation (mirrors SearchProjectionReconciler's own posture): a
                    // transient DB blip on ANY one (tenant, index) pair — including one that surfaces
                    // from deep inside rebuild()'s own Jdbc calls, none of which are behind rebuild's
                    // narrower try/catch — must not abort every OTHER pair scheduled this tick. Task
                    // 15's tick-level catch (not yet landed) only stops a bad TICK from crashing the
                    // scheduler; it does nothing for pairs still queued in the SAME tick.
                    try {
                        CatchUpResult first = dispatch(tenantId, index, sink.get(),
                            Optional.ofNullable(states.get(index)), pendingMarks.contains(index));
                        if (first == CatchUpResult.PROGRESSED) {
                            rotation.add(new ExtraCatchUp(tenantId, index, states.get(index)));
                        }
                    } catch (Exception ex) {
                        LOG.warnf(ex, "SearchProjectionWorker: dispatch failed for (%d, %s) — will retry next tick",
                            tenantId, index);
                    }
                }
            }
        }

        drainExtraRounds(sink.get(), rotation, tickStartNanos);
        recordLagGauges();
    }

    /** One pair still in the extra-rounds rotation (#754); {@code extra} flips once an extra batch has progressed. */
    private static final class ExtraCatchUp {
        final long tenantId;
        final String index;
        final SearchProjectionState state;
        boolean extra;
        ExtraCatchUp(long tenantId, String index, SearchProjectionState state) {
            this.tenantId = tenantId;
            this.index = index;
            this.state = state;
        }
    }

    /**
     * #754: after the historical pass (one attempt per pair, so a huge pair never takes another's turn), keep giving
     * every still-progressing pair one more batch per round, round-robin, until each pair is idle, fails, or needs a
     * rebuild, or until {@link #catchupBudget} — measured from the start of the tick on the monotone {@link #nanoClock}
     * — is spent. The budget is checked before each batch starts and never interrupts one; a budget of zero is this
     * method returning at once.
     */
    private void drainExtraRounds(ProjectionSink sink, Deque<ExtraCatchUp> rotation, long tickStartNanos) {
        long budgetNanos = catchupBudget.toNanos();
        if (budgetNanos <= 0) return;
        while (!rotation.isEmpty()) {
            ExtraCatchUp pair = rotation.poll();
            if (nanoClock.getAsLong() - tickStartNanos >= budgetNanos) {
                leaveRotation(sink, pair);
                rotation.forEach(p -> leaveRotation(sink, p));
                return;
            }
            CatchUpResult result;
            try {
                result = catchUp(pair.tenantId, pair.index);
            } catch (Exception ex) {
                LOG.warnf(ex, "SearchProjectionWorker: extra catch-up failed for (%d, %s) — will retry next tick",
                    pair.tenantId, pair.index);
                continue; // same fault isolation as the first pass: this pair leaves, the others go on
            }
            switch (result) {
                case PROGRESSED -> { pair.extra = true; rotation.add(pair); }
                case IDLE -> leaveRotation(sink, pair);
                case FAILED -> { /* recorded by runCatchUp; no retry within this tick, no advance */ }
                case REBUILD -> {
                    try {
                        rebuild(pair.tenantId, pair.index);
                    } catch (Exception ex) {
                        LOG.warnf(ex, "SearchProjectionWorker: rebuild failed for (%d, %s) — will retry next tick",
                            pair.tenantId, pair.index);
                    }
                }
            }
        }
    }

    /** A pair that progressed in extra rounds has moved since its first-pass advance: advance its asOf once more. */
    private void leaveRotation(ProjectionSink sink, ExtraCatchUp pair) {
        if (pair.extra) tryAdvanceAsOf(pair.tenantId, pair.index, sink, pair.state);
    }

    /**
     * #703's own Prometheus gauges (design doc §6): the deployment-wide max lag and lagging-pair
     * count, computed live from every (tenant, index) pair's oldest undrained mark — one extra admin
     * query per tick ({@link SearchProjectionMarkStore#oldestPendingMarkedAtAdmin()}), independent of
     * the dispatch loop above (which never fetches {@code marked_at} itself). A pair absent from that
     * query has zero lag.
     */
    private void recordLagGauges() {
        Map<Long, Map<String, Instant>> oldestByTenant = markStore.oldestPendingMarkedAtAdmin();
        long maxLag = 0;
        long lagging = 0;
        Instant now = Instant.now();
        for (Map<String, Instant> byIndex : oldestByTenant.values()) {
            for (Instant oldest : byIndex.values()) {
                // Every entry here already has at least one pending, undrained mark —
                // oldestPendingMarkedAtAdmin's own GROUP BY only returns pairs that do — so presence
                // alone is what counts as lagging (Copilot finding): gating on lagSeconds > 0
                // undercounted a mark landed within the same second as `now`, briefly reporting
                // lagging=0 during a burst even though work was genuinely pending.
                lagging++;
                maxLag = Math.max(maxLag, Math.max(0, Duration.between(oldest, now).getSeconds()));
            }
        }
        metrics.recordLag(maxLag, lagging);
    }

    /** Returns the first catch-up attempt's result ({@code IDLE} when none ran), so the tick can queue progressing pairs.
     *  Package-private: {@code SearchProjectionCatchupBacklogIT} drives one pair through it, the tick being cross-tenant. */
    CatchUpResult dispatch(long tenantId, String index, ProjectionSink sink,
                   Optional<SearchProjectionState> state, boolean hasPendingMark) {
        boolean needsRebuild = state.isEmpty()
            || state.get().liveGeneration() == null
            || !sink.name().equals(state.get().providerName())
            || hasPendingMark && "ready".equals(state.get().status()) && backlogExceedsThreshold(tenantId, index);
        if (needsRebuild) {
            rebuild(tenantId, index);
            return CatchUpResult.IDLE;
        }
        CatchUpResult result = CatchUpResult.IDLE;
        if (hasPendingMark) {
            // catchUp's own RunLock is already released by the time it returns (try-with-resources
            // inside catchUp's own method body) — rebuild below is a genuinely fresh, separate call,
            // never made while catch-up's lock is still held (see catchUp's own javadoc).
            result = catchUp(tenantId, index);
            if (result == CatchUpResult.REBUILD) {
                rebuild(tenantId, index);
                return CatchUpResult.IDLE; // a fresh rebuild re-bases asOf itself
            }
        }
        tryAdvanceAsOf(tenantId, index, sink, state.get());
        return result;
    }

    /** #894: a backlog past {@link #rebuildBacklogThreshold} takes the rebuild an {@code ALL} mark leads to, whose
     *  own watermark keeps every mark landed after it. Asked for a {@code ready} pair only: a failed rebuild leaves
     *  the pair degraded with its live generation, and it then drains batch by batch rather than retrying, every
     *  tick, a rebuild that may never succeed. */
    private boolean backlogExceedsThreshold(long tenantId, String index) {
        if (rebuildBacklogThreshold == 0 || !markStore.pendingMarksExceed(tenantId, index, rebuildBacklogThreshold)) return false;
        LOG.infof("SearchProjectionWorker: (%d, %s) has more than %d pending changes — rebuilding instead of catching up",
            tenantId, index, rebuildBacklogThreshold);
        return true;
    }

    /** #730: never fails the tick — the stored instant just stays where it was. Skipped while the
     *  pair is not {@code ready} (a stale {@code degraded} snapshot only delays the advance one tick). */
    private void tryAdvanceAsOf(long tenantId, String index, ProjectionSink sink, SearchProjectionState state) {
        if (advancer == null || !"ready".equals(state.status()) || state.liveGeneration() == null) return;
        try {
            advancer.advance(tenantId, index, sink, state.liveGeneration());
        } catch (RuntimeException e) {
            LOG.warnf(e, "SearchProjectionWorker: asOf advance failed for (%d, %s) — will retry next tick", tenantId, index);
        }
    }

    /**
     * The pair's RunLock, attempted once — shared by {@link #rebuild}, {@link #catchUp} and {@link
     * #purgeDrain}'s own per-row loop, each of which otherwise re-wrote the identical
     * acquire-or-log-and-give-up boilerplate. Empty both when the attempt itself fails (logged here,
     * under {@code opName}) and when another replica already holds the lock this tick (never logged —
     * that is the expected, silent "someone else has it" outcome); either way the caller's own
     * early-exit value is its own business, not this method's.
     */
    private Optional<RunLock> tryLock(String opName, long tenantId, String index) {
        UUID lockKey = SearchProjectionLockKey.of(tenantId, index);
        try {
            return Optional.ofNullable(RunLock.tryAcquire(adminDs, lockKey));
        } catch (SQLException e) {
            LOG.warnf(e, "SearchProjectionWorker.%s: could not attempt the lock for (%d, %s)", opName, tenantId, index);
            return Optional.empty();
        }
    }

    // -------------------------------------------------------------------------------------------
    // §5b: full rebuild.
    // -------------------------------------------------------------------------------------------

    /**
     * §5b steps 0-6. Claims the pair's RunLock first — before any state-row write, so a row is
     * never visible with a free lock. Reads the mark table's head (the watermark) BEFORE re-reading
     * the declaration (step 1 before step 2): a SearchIndex re-apply landing between those two reads
     * would otherwise let a rebuild started against the OLD manifest delete marks a newer generation
     * still needs, since watermark-after-declaration would capture marks the re-apply itself may have
     * produced. Reading the watermark first means any mark produced after it is preserved by the
     * exact-id deletion in {@link #runBuild}, whichever declaration wins the race. The cleanup deletes
     * the ids visible at that read, never {@code id <= head}: a lower-id mark whose transaction commits
     * only after the scan opened its snapshot is not in the rebuilt generation and must survive (#969).
     */
    void rebuild(long tenantId, String index) {
        Optional<RunLock> lockOpt = tryLock("rebuild", tenantId, index);
        if (lockOpt.isEmpty()) return; // could not attempt it, or another replica already owns this pair this tick
        RunLock lock = lockOpt.get();

        try (lock) {
            Instant baseAsOf = captureBaseAsOf(); // #730: BEFORE the watermark read and before the scan opens
            beforeWatermarkRead.run();
            List<Long> markIds = markStore.pendingMarkIds(tenantId, index); // step 1: BEFORE the declaration re-read
            afterWatermarkRead.run();
            Optional<SearchIndexDeclaration> decl = readDeclaration(tenantId, index); // step 2
            if (decl.isEmpty() || !"valid".equals(decl.get().status())) {
                return; // gone or invalid since discovery's own snapshot — not this method's job
            }
            ClaimedRun run = transitionState(tenantId, index);
            runBuild(tenantId, index, decl.get(), markIds, baseAsOf, run);
        }
    }

    /**
     * §3.1's on-demand entry point — the ONLY caller is the REST layer (Task 9), never discoveryTick.
     * Entirely synchronous through the claim: nothing about this call is allowed to answer Started and
     * then silently do nothing. {@code authorizedSnapshot} is the declaration the REST layer's own
     * caller was authorized against (e.g. by a permission check reading it earlier); this method
     * re-reads {@code core.search_index} itself and refuses with {@link RebuildClaim.DeclarationChanged}
     * if the two disagree, rather than trusting a snapshot that may already be stale by the time this
     * runs.
     *
     * <p>#703 finding: {@link #rebuildPermits} is attempted FIRST — before {@code RunLock.tryAcquire}
     * — cheaper to fail fast on permit exhaustion than to take a lock (a pooled {@code admin}
     * connection) only to immediately give it back. Every path below that follows a successful
     * {@code tryAcquire()} releases that exact permit exactly once: the synchronous portion holds it
     * under one {@code try/finally} (released on every early return or thrown exception), and
     * ownership passes to the async task's own {@code finally} only once submission has actually
     * succeeded — released there only after {@code runBuild} has returned and the {@link RunLock}
     * itself has already been closed.
     */
    public RebuildClaim triggerRebuild(long tenantId, String index, SearchIndexDeclaration authorizedSnapshot) {
        if (!rebuildPermits.tryAcquire()) {
            return new RebuildClaim.TooManyInFlight();
        }

        boolean releasePermit = true;
        try {
            UUID lockKey = SearchProjectionLockKey.of(tenantId, index);
            RunLock lock;
            try {
                lock = RunLock.tryAcquire(adminDs, lockKey);
            } catch (SQLException e) {
                throw new IllegalStateException("could not attempt the lock for (" + tenantId + ", " + index + ")", e);
            }
            if (lock == null) {
                return stateStore.find(tenantId, index)
                    .map(SearchProjectionState::activeRunId)
                    .<RebuildClaim>map(RebuildClaim.RebuildAlreadyRunning::new)
                    .orElseGet(RebuildClaim.PairBusy::new);
            }

            List<Long> markIds;
            Instant baseAsOf;
            Optional<SearchIndexDeclaration> decl;
            ClaimedRun run;
            try {
                baseAsOf = captureBaseAsOf(); // #730: same ordering as rebuild() — before the watermark read
                beforeWatermarkRead.run();
                markIds = markStore.pendingMarkIds(tenantId, index); // same ordering invariant as rebuild()
                afterWatermarkRead.run();
                decl = readDeclaration(tenantId, index);
                boolean matches = decl.isPresent() && "valid".equals(decl.get().status())
                    && decl.get().entity().equals(authorizedSnapshot.entity())
                    && Objects.equals(decl.get().manifest(), authorizedSnapshot.manifest());
                if (!matches) {
                    lock.close();
                    return new RebuildClaim.DeclarationChanged();
                }
                run = transitionState(tenantId, index);
            } catch (RuntimeException e) {
                // The watermark read, declaration re-read and transitionState's own claim can each throw a
                // genuine RuntimeException (Jdbc.update/query/queryOne all wrap a checked SQLException) —
                // RunLock.close() is the only path that releases the advisory lock and returns/destroys the
                // connection, so any of these leaving without it strands both (code review finding).
                lock.close();
                throw e;
            }

            try {
                rebuildExecutor.submit(() -> {
                    try (RunLock ignored = lock) {
                        runBuild(tenantId, index, decl.get(), markIds, baseAsOf, run);
                    } finally {
                        rebuildPermits.release();
                    }
                });
            } catch (RuntimeException submitFailure) {
                // The claim (transitionState above) is already durable; submit() itself failing (executor
                // shutting down) must not strand it — write the terminal outcome synchronously and propagate.
                stateStore.markDegradedEndingRun(tenantId, index, shortDiagnostic(submitFailure), run.runId());
                metrics.recordRebuildTerminal("failed", Duration.between(run.startedAt(), Instant.now()));
                lock.close();
                throw submitFailure;
            }
            releasePermit = false; // ownership transferred to the async task's own finally, above
            return new RebuildClaim.Started(run.runId());
        } finally {
            if (releasePermit) rebuildPermits.release();
        }
    }

    /**
     * A rebuild's own (runId, startedAt) pair, claimed together by {@link #transitionState} —
     * {@code active_run_started_at}'s own value (design doc §2), generated in application code
     * rather than read back from Postgres so the worker never needs an extra round trip to compute
     * duration. Package-private, not private: {@code SearchProjectionWorkerRebuildTest} (same
     * package) constructs one directly, exactly like {@link SearchIndexDeclaration}'s own visibility.
     */
    record ClaimedRun(UUID runId, Instant startedAt) {}

    /**
     * Steps 3 through 6, given an already re-read, valid declaration, the mark ids read ahead of
     * it ({@code markIds}, oldest first; the last is the watermark), the freshness base ({@code baseAsOf}, #730) captured before both the watermark and the scan,
     * and a run already claimed by the caller ({@link #transitionState}) — both {@link #rebuild}
     * and {@link #triggerRebuild} claim their own run before calling in, so this method never claims
     * one itself. Scans {@code decl.entity()} — the freshly re-read declaration, not discovery's own
     * snapshot: a SearchIndex re-apply landing between discovery's snapshot and this fresh read may
     * have repointed it at a different entity, and {@code decl} is the one source re-read
     * specifically so nothing downstream trusts a stale value.
     *
     * <p>Package-private: the seam {@code SearchProjectionWorkerRebuildTest} uses to exercise the
     * sink-resolve / begin-generation / scan / publish-or-abandon control flow directly, without
     * RunLock or the (Postgres-backed) declaration re-read — both exercised instead by the
     * {@code @QuarkusTest} IT.
     */
    void runBuild(long tenantId, String index, SearchIndexDeclaration decl, List<Long> markIds, Instant baseAsOf,
                  ClaimedRun run) {
        Optional<ProjectionSink> resolved = gate.resolve();
        if (resolved.isEmpty()) {
            // Discovery's own check could be stale by lock-acquisition time; an absent provider
            // mid-tick is rare enough that treating it like any other failure (step 6) is the
            // correct fallback — nothing was begun yet, so there is nothing to abandon.
            stateStore.markDegradedEndingRun(tenantId, index, "no projection sink resolved", run.runId());
            metrics.recordRebuildTerminal("failed", Duration.between(run.startedAt(), Instant.now()));
            return;
        }
        ProjectionSink sink = resolved.get();

        String gen = null;
        try {
            gen = sink.beginGeneration(tenantId, index, ProjectionDefinitions.from(decl.manifest()));
            stateStore.recordActiveGeneration(tenantId, index, gen, sink.name());
            rowScanner.scan(tenantId, index, decl.entity(), decl.manifest(), sink, gen, run.runId());

            if (!stateStore.casToPublishing(tenantId, index, run.runId())) {
                // §3.2's pre-publish CAS lost the race: a cancel request committed between the scan's
                // last between-batch check and here. sink.publish was never attempted, so `gen` is
                // still definitely just a staging generation — the staging generation is still
                // abandoned, but this is a cancellation, not a failure.
                abandonQuietly(sink, tenantId, index, gen);
                recordCancelled(tenantId, index, run);
                return;
            }
        } catch (RebuildCancelledException cancelled) {
            // The between-batch check (drainAndUpsert -> checkCancellation) fired mid-scan, strictly
            // before casToPublishing/publish are ever reached — `gen` is still just staging.
            abandonQuietly(sink, tenantId, index, gen);
            recordCancelled(tenantId, index, run);
            return;
        } catch (Exception e) {
            // Everything up to and including casToPublishing itself failing is still pre-publish:
            // the sink was never told to switch generations, so `gen` is still just staging.
            abandonQuietly(sink, tenantId, index, gen);
            stateStore.markDegradedEndingRun(tenantId, index, shortDiagnostic(e), run.runId());
            metrics.recordRebuildTerminal("failed", Duration.between(run.startedAt(), Instant.now()));
            return;
        }

        try {
            sink.publish(tenantId, index, gen, baseAsOf); // step 5 (#730: base written atomically with the cutover)
        } catch (Exception e) {
            // Code review finding: "publish is atomic" is the SPI's own guarantee that the SWITCH
            // itself is all-or-nothing at the provider, never a promise that a caller who sees this
            // call throw can tell a lost commit from a real rollback (an HTTP provider's response
            // dropping after it already committed server-side is the ordinary shape of this). The
            // reaper already treats a crash in this same phase as ambiguous (reapPublishing's own
            // javadoc) — this synchronous path must not assume otherwise: never abandon `gen` here,
            // it may already be live at the provider. markDegradedClearingGeneration, same
            // conservative posture as the post-publish markReady failure below.
            stateStore.markDegradedClearingGeneration(tenantId, index, shortDiagnostic(e), run.runId());
            metrics.recordRebuildTerminal("failed", Duration.between(run.startedAt(), Instant.now()));
            return;
        }

        try {
            commitMarkCleanupAndMarkReady(tenantId, index, sink.name(), gen, markIds, run.runId()); // step 5, atomically
        } catch (Exception e) {
            // `gen` is already the live generation at this point (publish above already succeeded) --
            // never abandon it here, only record the real cause so the field sees it instead of the
            // reaper's generic "reaped" message once it next finds this row still stuck in `building`.
            // markDegradedClearingGeneration, not markDegradedEndingRun (code review, #671): transitionState
            // left whatever live_generation this row had BEFORE this attempt untouched, but the sink
            // has already moved past it -- leaving that stale value in place would let the next tick's
            // dispatch() see a non-null liveGeneration and try an incremental catch-up against a
            // generation the sink no longer recognizes as live, instead of the full rebuild this
            // degraded state actually needs.
            stateStore.markDegradedClearingGeneration(tenantId, index, shortDiagnostic(e), run.runId());
            metrics.recordRebuildTerminal("failed", Duration.between(run.startedAt(), Instant.now()));
            return;
        }
        metrics.recordRebuildTerminal("ready", Duration.between(run.startedAt(), Instant.now()));
    }

    /** Best-effort abandon shared by both cancellation exit points in {@link #runBuild} — a no-op
     *  when {@code gen} is still null (cancellation surfaced before {@code beginGeneration} ever
     *  returned, which cannot happen today but costs nothing to guard). */
    private void abandonQuietly(ProjectionSink sink, long tenantId, String index, String gen) {
        if (gen == null) return;
        try {
            sink.abandon(tenantId, index, gen);
        } catch (RuntimeException ignore) {
            // best-effort: the sink already failed once this attempt
        }
    }

    /** The abandon-and-record-cancelled shape shared by both cancellation exit points in {@link
     *  #runBuild} (the between-batch check and the pre-publish CAS): whether this run reverts the
     *  row to {@code ready} depends on whether a prior build had already made a generation live
     *  before this attempt began. */
    private void recordCancelled(long tenantId, String index, ClaimedRun run) {
        boolean hadPriorLiveGeneration = stateStore.find(tenantId, index)
            .map(SearchProjectionState::liveGeneration).isPresent();
        stateStore.markCancelled(tenantId, index, run.runId(), hadPriorLiveGeneration);
        metrics.recordRebuildTerminal("cancelled", Duration.between(run.startedAt(), Instant.now()));
    }

    /**
     * §5b step 5's mark-cleanup + markReady tail, in ONE explicit transaction (I3 fix, #671 final
     * review) via {@link Jdbc#transaction} — the same setAutoCommit/commit/rollback shape {@code
     * io.lumnik.hub.search.SearchIndexRevalidator}'s own {@code invalidateIfUnchanged} still hand-rolls
     * separately (pre-existing, untouched by #671, so left as its own copy rather than migrated here).
     * Two independently-autocommitted statements would let the mark-cleanup commit alone if the state
     * write then failed transiently: {@code publish} above has already dropped the OLD generation
     * from the provider's side, so a state row left pointing at it (with the {@code ALL} mark that
     * would force a fresh rebuild already deleted) would be stuck forever.
     */
    private void commitMarkCleanupAndMarkReady(long tenantId, String index, String providerName, String gen,
                                                List<Long> markIds, UUID runId) {
        long watermark = markIds.isEmpty() ? 0L : markIds.get(markIds.size() - 1);
        Jdbc.transaction(adminDs, "SearchProjectionWorker.commitMarkCleanupAndMarkReady", c -> {
            markStore.deleteByExactIds(c, tenantId, index, markIds);
            stateStore.markReady(c, tenantId, index, providerName, gen, watermark, runId);
        });
    }

    /** One (tenant, index) full-rebuild declaration, freshly re-read — never discovery's stale
     *  snapshot. {@code manifest} is null unless {@code status} is {@code valid}. Public: Task 9's
     *  REST resource, in a different package, both receives one from {@link #triggerRebuild}'s
     *  caller and constructs one as the authorized snapshot. */
    public record SearchIndexDeclaration(String status, String entity, SearchIndexManifest manifest) {}

    private Optional<SearchIndexDeclaration> readDeclaration(long tenantId, String index) {
        return Jdbc.queryOne(adminDs, "SearchProjectionWorker.readDeclaration",
            "SELECT status, entity, manifest_yaml FROM core.search_index WHERE tenant_id = ? AND name = ?",
            ps -> { ps.setLong(1, tenantId); ps.setString(2, index); },
            rs -> new SearchIndexDeclaration(rs.getString(1), rs.getString(2),
                "valid".equals(rs.getString(1))
                    ? new SearchIndexManifestParser().parse(rs.getString(3))
                    : null));
    }

    /** §5b step 2's own transition: no state row yet -> insert building; existing ready/degraded row
     *  -> building with {@code last_error} cleared in the same statement (a carried-over degraded
     *  error would violate the check constraint the instant this runs against real Postgres). Mints
     *  and returns this attempt's own (runId, startedAt) claim — {@link #rebuild} and {@link
     *  #triggerRebuild} are the only two callers, each claiming a fresh run right before {@link
     *  #runBuild}. */
    private ClaimedRun transitionState(long tenantId, String index) {
        UUID runId = UUID.randomUUID();
        Instant startedAt = Instant.now();
        if (stateStore.find(tenantId, index).isPresent()) {
            stateStore.transitionToBuilding(tenantId, index, runId, startedAt);
        } else {
            stateStore.insertBuilding(tenantId, index, runId, startedAt);
        }
        return new ClaimedRun(runId, startedAt);
    }

    /** What {@code Last error} shows: the failure itself, not the transaction wrapper around it. */
    private static String shortDiagnostic(Exception e) {
        Throwable t = e;
        while (t instanceof QuarkusTransactionException && t.getCause() != null) t = t.getCause();
        return t.getClass().getSimpleName() + ": " + (t.getMessage() == null ? "" : t.getMessage());
    }

    /** Feeds documents to {@code sink.upsert} for a full rebuild's scan (§5b step 4); the real
     *  implementation is {@link #scanAndUpsert}, JDBC-backed — {@link #rowScanner}'s test seam
     *  substitutes a fake. */
    @FunctionalInterface
    interface RowScanner {
        void scan(long tenantId, String index, String entity, SearchIndexManifest manifest,
                  ProjectionSink sink, String gen, UUID runId) throws Exception;
    }

    /**
     * The server-side cursor scan (§5b step 4): ambient in a timeout-bounded {@code requiringNew()}
     * transaction under the tenant, exactly mirroring {@code ExportRunner.generate}'s own
     * {@code TenantScope.get} + {@code QuarkusTransaction.requiringNew().timeout(...)} +
     * {@code TenantRlsScope.applyTo} pattern. A {@code resolution:} entity's still-unresolved rows
     * ({@code _entity_id IS NULL}) are excluded by {@link #buildScanSql} — they are never projected.
     * {@code runId} is unused this task — Task 5 uses it in {@link #drainAndUpsert} for the
     * between-batch cancellation check.
     */
    private void scanAndUpsert(long tenantId, String index, String entity, SearchIndexManifest manifest,
                               ProjectionSink sink, String gen, UUID runId) {
        TenantScope.get(tenantId, () -> QuarkusTransaction.requiringNew().timeout(rebuildScanTimeoutSeconds)
            .call(() -> {
                EntityScanContext ctx = resolveScanContext(entity,
                    "entity '" + entity + "' not found in EntityRegistry — cannot rebuild search index " + index);
                String sql = buildScanSql(entity, manifest, ctx.resolutionMode(), ctx.matchColumns(), ctx.keyCol());

                try (Connection c = readonlyDs.getConnection()) {
                    TenantRlsScope.applyTo(c, tenantId);
                    try (PreparedStatement ps = c.prepareStatement(sql)) {
                        ps.setFetchSize(SCAN_BATCH_SIZE);
                        try (ResultSet rs = ps.executeQuery()) {
                            drainAndUpsert(rs, manifest.returnableFields(), ctx.resolutionMode(), ctx.matchColumns(),
                                ctx.keyCol(), tenantId, index, sink, gen, runId);
                        }
                    } catch (SQLException e) {
                        throw explainUnreadableView(tenantId, entity, ctx.keyCol(), e);
                    }
                    return null;
                }
            }));
    }

    /** One (tenant, index) scan's own entity-mode derivation — shared by {@link #scanAndUpsert} (full
     *  rebuild) and {@link #readBatch} (catch-up), which otherwise each re-derived the identical
     *  {@code resolutionMode}/{@code matchColumns}/{@code keyCol} triple from the same {@link
     *  EntityManifest}. */
    private record EntityScanContext(boolean resolutionMode, List<String> matchColumns, String keyCol) {}

    /**
     * A view compiled before the column the projection reads existed (#842) fails the scan with
     * "column _canonical_key does not exist": a message about SQL and a column the installer never wrote.
     * Only that case — an undefined column that IS the key column — is turned into the gesture that fixes
     * it; any other undefined column stays what the database said, so a different mistake is never
     * mislabelled as this one. The failed statement has aborted the scan's own transaction, hence the
     * probe on a connection of its own; if it cannot tell, the original failure stands.
     */
    private Exception explainUnreadableView(long tenantId, String entity, String keyCol, SQLException failure) {
        if (!"42703".equals(failure.getSQLState())) return failure;
        try (Connection c = readonlyDs.getConnection()) {
            // Like every raw read on this datasource: the tenant bound and an empty search_path. The caller is
            // still inside the scan's JTA transaction, which is what applyTo requires.
            TenantRlsScope.applyTo(c, tenantId);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM pg_attribute WHERE attrelid = ?::regclass AND attname = ? AND attnum > 0 AND NOT attisdropped")) {
                ps.setString(1, EntityViewCompiler.viewName(entity));
                ps.setString(2, keyCol);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return failure; // the key column is there: another column is what is missing
                }
            }
        } catch (SQLException probe) {
            return failure;
        }
        return new IllegalStateException("entity '" + entity + "' cannot be indexed: its view has no \"" + keyCol
            + "\" column, because the entity was applied by a hub older than the one that added it — apply it again"
            + " (lm entity apply -f <manifest>), then rebuild", failure);
    }

    private EntityScanContext resolveScanContext(String entity, String failureMessage) {
        EntityManifest entityManifest = registry.findReloadingIfMissing(entity);
        if (entityManifest == null) {
            throw new IllegalStateException(failureMessage);
        }
        boolean resolutionMode = entityManifest.resolution() != null;
        List<String> matchColumns = resolutionMode || entityManifest.match() == null
            ? List.of() : entityManifest.match();
        String keyCol = resolutionMode ? "_entity_id" : "_canonical_key";
        return new EntityScanContext(resolutionMode, matchColumns, keyCol);
    }

    /**
     * {@code SELECT <keyCol>, <every returnable field, plus every match column not already among
     * them> FROM connector.v_<entity>} — shared by the full-rebuild scan ({@link #buildScanSql}) and
     * catch-up's own batched read ({@link #buildBatchSql}), each appending its own {@code
     * WHERE}. The match columns are selected explicitly alongside the returnable ones (deduplicated)
     * so a row can always be read back by name even when a match column is not itself declared
     * searchable/returnable on the SearchIndex.
     */
    private static String selectColumnsSql(String entity, SearchIndexManifest manifest, boolean resolutionMode,
                                            List<String> matchColumns, String keyCol) {
        String view = EntityViewCompiler.viewName(entity);
        LinkedHashSet<String> cols = new LinkedHashSet<>(manifest.returnableFields());
        if (!resolutionMode) cols.addAll(matchColumns);

        StringBuilder sql = new StringBuilder("SELECT ").append(SqlIdentifiers.quoteIdent(keyCol));
        for (String c : cols) sql.append(", ").append(SqlIdentifiers.quoteIdent(c));
        sql.append(" FROM ").append(view);
        return sql.toString();
    }

    /** {@link #selectColumnsSql}, {@code resolution:} mode adding {@code WHERE _entity_id IS NOT
     *  NULL} (Review Focus item 1 — an unresolved row is never projected). */
    private static String buildScanSql(String entity, SearchIndexManifest manifest, boolean resolutionMode,
                                        List<String> matchColumns, String keyCol) {
        StringBuilder sql = new StringBuilder(selectColumnsSql(entity, manifest, resolutionMode, matchColumns, keyCol));
        if (resolutionMode) {
            sql.append(" WHERE ").append(SqlIdentifiers.quoteIdent("_entity_id")).append(" IS NOT NULL");
        }
        return sql.toString();
    }

    /**
     * {@link #selectColumnsSql}, narrowed to the whole batch of canonical keys a catch-up tick is
     * applying at once — {@code _canonical_key = ANY(?)} for a {@code match:} entity, {@code
     * _entity_id::text = ANY(?)} for a {@code resolution:} one (the cast: {@code canonicalKey} is
     * always text — a mark row's own {@code canonical_key} column — while {@code _entity_id} is the
     * view's native uuid). ONE query per batch (design doc §5c step 3, I4 fix, #671 final review):
     * a resolution-mode view's {@code _entity_id} is an aggregate output under {@code GROUP BY}, so
     * a single-key predicate cannot be pushed below the aggregation — up to {@link
     * #CATCHUP_BATCH_SIZE} single-key queries per tick, each potentially a full view evaluation,
     * would not scale.
     */
    private static String buildBatchSql(String entity, SearchIndexManifest manifest, boolean resolutionMode,
                                         List<String> matchColumns, String keyCol) {
        String quotedKeyCol = SqlIdentifiers.quoteIdent(keyCol);
        String predicate = resolutionMode ? quotedKeyCol + "::text = ANY(?)" : quotedKeyCol + " = ANY(?)";
        return selectColumnsSql(entity, manifest, resolutionMode, matchColumns, keyCol) + " WHERE " + predicate;
    }

    /**
     * Drains the cursor in {@link #SCAN_BATCH_SIZE}-row batches into {@code sink.upsert}. Every
     * column — the key column and every field — is read by NAME (never a hardcoded ordinal), since
     * {@link #buildScanSql} is the only place that fixes the select list's shape. {@code runId}
     * backs the between-batch cancellation check (§3.2) run after every batch, including the final
     * partial one.
     */
    private void drainAndUpsert(ResultSet rs, List<String> returnableFields, boolean resolutionMode,
                                List<String> matchColumns, String keyCol, long tenantId, String index,
                                ProjectionSink sink, String gen, UUID runId) throws SQLException {
        List<ProjectionDocument> batch = new ArrayList<>();
        while (rs.next()) {
            batch.add(rowToDocument(rs, returnableFields, resolutionMode, matchColumns, keyCol));
            if (batch.size() >= SCAN_BATCH_SIZE) {
                sink.upsert(tenantId, index, gen, List.copyOf(batch));
                batch.clear();
                checkCancellation(tenantId, index, runId);
            }
        }
        if (!batch.isEmpty()) {
            sink.upsert(tenantId, index, gen, List.copyOf(batch));
            checkCancellation(tenantId, index, runId);
        }
    }

    /** {@link #drainAndUpsert}'s own between-batch check (§3.2): a cancel request committed by
     *  another request/thread while this scan was mid-flight surfaces here as a distinct, unchecked
     *  {@link RebuildCancelledException} — {@link #runBuild}'s own catch treats it apart from a real
     *  failure. */
    private void checkCancellation(long tenantId, String index, UUID runId) {
        if (stateStore.cancelRequestedFor(tenantId, index, runId)) {
            throw new RebuildCancelledException();
        }
    }

    /**
     * One row of {@link #buildScanSql}/{@link #buildBatchSql}'s own column shape, turned into a
     * {@link ProjectionDocument}. {@code key} is the decomposed, displayed form: {@code
     * {"_entity_id": id}} for a {@code resolution:} entity, or the entity's declared {@code match:}
     * column name/value pairs for a {@code match:} entity — read out of the same row by column name
     * via {@code EntityManifest.match()}, never assumed to sit at a fixed position.
     */
    private static ProjectionDocument rowToDocument(ResultSet rs, List<String> returnableFields, boolean resolutionMode,
                                                      List<String> matchColumns, String keyCol) throws SQLException {
        String id = rs.getString(keyCol);

        // A non-match returnable column can legitimately be NULL for a given row (e.g. an
        // array_agg(...) FILTER aggregate collapsing to NULL when no source has a value) — omit it
        // from the map rather than let ProjectionDocument's own compact constructor NPE on it
        // (C2 fix, #671 final review): the contract is right to refuse a null VALUE, the caller was
        // wrong to ever offer one.
        Map<String, String> fields = new LinkedHashMap<>();
        for (String rf : returnableFields) {
            String value = rs.getString(rf);
            if (value != null) fields.put(rf, value);
        }

        Map<String, String> key;
        if (resolutionMode) {
            key = Map.of("_entity_id", id);
        } else {
            key = new LinkedHashMap<>();
            for (String mc : matchColumns) key.put(mc, rs.getString(mc));
        }

        return new ProjectionDocument(id, key, fields);
    }

    // -------------------------------------------------------------------------------------------
    // §5c: incremental catch-up.
    // -------------------------------------------------------------------------------------------

    /** Batch size of {@link SearchProjectionMarkStore#fetchBatch} per catch-up tick — never
     *  the whole backlog at once (design doc §5c step 1). */
    static final int CATCHUP_BATCH_SIZE = 500;

    /**
     * The live batched read backing a batch's worth of {@code KEY} marks' re-projection (§5c step
     * 3), package-private and mutable exactly like {@link #rowScanner} — the real implementation is
     * {@link #readBatch}; {@code SearchProjectionWorkerCatchupTest} substitutes a fake so §5c's
     * batch logic ({@link #runCatchUp}) can be unit-tested without a database. Defaults to the real
     * JDBC-backed implementation. Returns a map keyed by canonical key; a key absent from the
     * result means its row is gone from the view (delete), present means re-project (upsert).
     */
    RowBatchReader rowBatchReader = this::readBatch;

    @FunctionalInterface
    interface RowBatchReader {
        Map<String, ProjectionDocument> read(long tenantId, String entity, SearchIndexManifest manifest,
                                              List<String> canonicalKeys) throws Exception;
    }

    /**
     * §5c steps 0-3: claims the pair's RunLock exactly like {@link #rebuild}, re-reads {@code
     * core.search_index} under the same "stale discovery" guard, then delegates the batch itself to
     * {@link #runCatchUp} — the seam {@code SearchProjectionWorkerCatchupTest} exercises directly,
     * without RunLock or a real Postgres connection (mirrors {@link #runBuild}'s own role for
     * {@link #rebuild}).
     *
     * <p>Returns {@link CatchUpResult#REBUILD} when an {@code ALL} mark surfaces mid-batch — the signal {@link
     * #dispatch} reads to call {@link #rebuild} in a FRESH, separate call, strictly after this
     * method has already returned. This method must never call {@link #rebuild} itself from in here:
     * this method's own {@code try (lock)} block is still open at that point, and {@code
     * RunLock.tryAcquire} always opens a brand-new connection — a new backend session — for every
     * call, never reusing the caller's own (Postgres advisory locks ARE reentrant within one
     * session; that is not what bites here). A nested call from inside catch-up's own held lock
     * would therefore open a genuinely DIFFERENT session that sees the key already held by this
     * very call's OUTER session and correctly fails, returning {@code null} — silently disabling the
     * rebuild forever, every tick, rather than deadlocking. Returning {@link CatchUpResult#REBUILD} and letting
     * {@code try (lock)} close on the way out — before the caller ever sees the return value — is
     * what makes {@link #dispatch}'s subsequent {@link #rebuild} call genuinely fresh.
     */
    CatchUpResult catchUp(long tenantId, String index) {
        Optional<RunLock> lockOpt = tryLock("catchUp", tenantId, index);
        if (lockOpt.isEmpty()) return CatchUpResult.IDLE; // could not attempt it, or another replica already owns this pair this tick
        RunLock lock = lockOpt.get();

        try (lock) {
            Optional<SearchIndexDeclaration> decl = readDeclaration(tenantId, index);
            if (decl.isEmpty() || !"valid".equals(decl.get().status())) return CatchUpResult.IDLE; // abandoned, same as §5b
            return runCatchUp(tenantId, index, decl.get());
        }
    }

    /**
     * §5c steps 1-3, given an already re-read, valid declaration. Package-private: the seam this
     * class's unit test drives directly, exactly like {@link #runBuild} does for {@link #rebuild}.
     *
     * <p>An {@code ALL} mark anywhere in the fetched batch abandons the WHOLE batch — no upsert/
     * delete, no mark deleted, no position advanced — and returns {@link CatchUpResult#REBUILD} rather than servicing
     * the {@code KEY} marks around it: a rebuild is coming for this pair regardless, and applying
     * stale {@code KEY} marks now only to have the rebuild's own scan overwrite them moments later
     * is wasted, racy work.
     *
     * <p>Marks actually applied are deleted by their EXACT fetched ids ({@link
     * SearchProjectionMarkStore#deleteByExactIds}) — never a live-recomputed {@code id <= max}
     * condition — and {@code position} is advanced only to the highest id ACTUALLY deleted, never
     * the batch's own max: a mark concurrently reissued a fresher id (Task 3's {@code DO UPDATE})
     * between this method's own fetch and its delete matches zero rows there, so it is never lost
     * — it simply sits above the new {@code position} and is picked up whole by the very next tick.
     * {@code position}'s own advance is monotone ({@link
     * SearchProjectionStateStore#advancePosition}'s {@code GREATEST}, #712): the batch itself is
     * fetched by {@link SearchProjectionMarkStore#fetchBatch}, oldest id first with no {@code
     * position} filter, so a lower-id mark whose producing transaction committed after this row's
     * own {@code position} had already advanced past a higher id is still fetched and applied here.
     */
    CatchUpResult runCatchUp(long tenantId, String index, SearchIndexDeclaration decl) {
        SearchProjectionState state = stateStore.find(tenantId, index).orElseThrow();
        List<MarkRow> batch = markStore.fetchBatch(tenantId, index, CATCHUP_BATCH_SIZE);
        if (batch.isEmpty()) return CatchUpResult.IDLE;
        if (batch.stream().anyMatch(m -> m.kind() == MarkKind.ALL)) {
            return CatchUpResult.REBUILD; // caller dispatches a FRESH rebuild() call, outside this lock
        }
        afterMarkBatchFetch.run();

        Optional<ProjectionSink> resolved = gate.resolve();
        if (resolved.isEmpty()) {
            // Discovery's own check could be stale by lock-acquisition time, same posture as rebuild's.
            stateStore.markDegraded(tenantId, index, "no projection sink resolved");
            return CatchUpResult.FAILED;
        }
        ProjectionSink sink = resolved.get();

        List<Long> appliedIds = new ArrayList<>();
        Exception failure = null;
        try {
            List<String> keys = batch.stream().map(MarkRow::canonicalKey).toList();
            Map<String, ProjectionDocument> rows =
                rowBatchReader.read(tenantId, decl.entity(), decl.manifest(), keys);
            applyBatch(tenantId, index, sink, batch, state.liveGeneration(), rows, appliedIds);
        } catch (Exception e) {
            failure = e; // partial success still commits below — never lost
        }

        if (!appliedIds.isEmpty()) {
            long deletedMax = markStore.deleteByExactIds(tenantId, index, appliedIds); // exact ids only
            if (deletedMax > 0) { // 0 = every applied id was concurrently reissued; position must not move
                stateStore.advancePosition(tenantId, index, deletedMax);
            }
        }
        if (failure != null) {
            stateStore.markDegraded(tenantId, index, shortDiagnostic(failure));
            metrics.recordCatchupFailure();
        } else if ("degraded".equals(state.status())) {
            // I2 fix (#671 final review): a later successful batch clears a prior failure's
            // status/retry_count/last_error (design doc §5c step 5) — a pair that keeps succeeding
            // every tick must not be reported degraded forever off one stale attempt.
            stateStore.markReadyAfterCatchup(tenantId, index);
        }
        return failure != null ? CatchUpResult.FAILED : CatchUpResult.PROGRESSED;
    }

    /**
     * #893: the batch in two provider calls — one {@code upsert} of every row still in the view, one
     * {@code delete} of every key gone from it — instead of one call per mark (a commit each on the
     * PostgreSQL provider, a forced refresh each on OpenSearch). Each key is decided exactly as {@link
     * #applyOneKey} decides it, and a batch holds each key once (one {@code KEY} mark per key, V56's
     * {@code UNIQUE}), so the two calls never touch the same document. A call that throws falls back to
     * {@link #applyOneKey} over the marks not applied yet — the whole batch when the upsert call threw,
     * only the deletes when the delete call did — in batch order. #937: a mark the provider refuses stays
     * for the next tick and the marks behind it still apply, so one refused document cannot pin the pair;
     * a run of {@link #catchupConsecutiveFailureLimit} failures in a row (any success resets it) stops
     * the pass, the marks it never reached staying too, and the first failure is rethrown once the pass
     * ends so the pair is reported degraded. Re-applying a document the failed call did write (OpenSearch's {@code _bulk} is not atomic) is
     * idempotent. Logged at WARN: a provider that refuses every multi-document call would otherwise
     * silently pay the per-mark cost on every tick. {@code appliedIds} receives every mark id applied,
     * whether or not this method then throws.
     */
    private void applyBatch(long tenantId, String index, ProjectionSink sink, List<MarkRow> batch,
                            String liveGeneration, Map<String, ProjectionDocument> rows, List<Long> appliedIds) {
        List<MarkRow> upserts = new ArrayList<>();
        List<MarkRow> deletes = new ArrayList<>();
        for (MarkRow mark : batch) (rows.get(mark.canonicalKey()) != null ? upserts : deletes).add(mark);
        List<MarkRow> remaining = batch;
        try {
            if (!upserts.isEmpty()) {
                sink.upsert(tenantId, index, liveGeneration, upserts.stream().map(m -> rows.get(m.canonicalKey())).toList());
                upserts.forEach(m -> appliedIds.add(m.id()));
                remaining = deletes;
            }
            if (!deletes.isEmpty()) {
                sink.delete(tenantId, index, liveGeneration, deletes.stream().map(MarkRow::canonicalKey).toList());
                deletes.forEach(m -> appliedIds.add(m.id()));
            }
        } catch (RuntimeException batchFailure) {
            LOG.warnf(batchFailure, "SearchProjectionWorker: batched catch-up call failed for (%d, %s) — retrying mark by mark",
                tenantId, index);
            RuntimeException firstRefusal = null;
            int consecutiveFailures = 0;
            for (MarkRow mark : remaining) {
                try {
                    applyOneKey(tenantId, index, sink, mark, liveGeneration, rows.get(mark.canonicalKey()));
                    appliedIds.add(mark.id());
                    consecutiveFailures = 0;
                } catch (RuntimeException refusal) {
                    if (firstRefusal == null) firstRefusal = refusal;
                    if (++consecutiveFailures >= catchupConsecutiveFailureLimit) break;
                }
            }
            if (firstRefusal != null) throw firstRefusal;
        }
    }

    /** One {@code KEY} mark's own re-projection, given the batch's already-fetched rows: {@code doc}
     *  present (the view still has a row for this key) upserts it; {@code doc} null (deleted, or
     *  mutated out of the entity's filter) deletes from the sink by the mark's own canonical key
     *  alone — {@code ProjectionSink.delete} takes only an id, never a reconstructed key map
     *  (D-document-identity). */
    private void applyOneKey(long tenantId, String index, ProjectionSink sink, MarkRow mark, String liveGeneration,
                             ProjectionDocument doc) {
        if (doc != null) {
            sink.upsert(tenantId, index, liveGeneration, List.of(doc));
        } else {
            sink.delete(tenantId, index, liveGeneration, List.of(mark.canonicalKey()));
        }
    }

    /**
     * The real, JDBC-backed {@link #rowBatchReader}: ONE query against {@code connector.v_<entity>}
     * for the whole batch of canonical keys, ambient in its own {@code requiringNew()} transaction
     * under the tenant — the same {@code TenantScope.get} + {@code QuarkusTransaction.requiringNew()}
     * + {@code TenantRlsScope.applyTo} pattern {@link #scanAndUpsert} uses for the full-rebuild scan,
     * minus the server-side cursor (one bounded result set, not a stream).
     *
     * <p>Under the rebuild scan's own budget, {@link #rebuildScanTimeoutSeconds} (#894): a batch reads about as much
     * as the whole view, so on the container's 60 s default every batch fails past a few million rows.
     */
    private Map<String, ProjectionDocument> readBatch(long tenantId, String entity, SearchIndexManifest manifest,
                                                        List<String> canonicalKeys) {
        return TenantScope.get(tenantId, () -> QuarkusTransaction.requiringNew().timeout(rebuildScanTimeoutSeconds).call(() -> {
            EntityScanContext ctx = resolveScanContext(entity,
                "entity '" + entity + "' not found in EntityRegistry — cannot catch up search index");
            String sql = buildBatchSql(entity, manifest, ctx.resolutionMode(), ctx.matchColumns(), ctx.keyCol());

            try (Connection c = readonlyDs.getConnection()) {
                TenantRlsScope.applyTo(c, tenantId);
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    // #966: never a server-named statement. From its 10th execution on one connection PostgreSQL
                    // would switch it to a generic plan, whose ANY($1) array is not hashed — 500 comparisons per
                    // source row, ~17x a custom plan. An unnamed statement is planned with its bound array every time
                    // (a SET LOCAL plan_cache_mode would need a real transaction, which %test's readonly lacks).
                    ps.unwrap(PGStatement.class).setPrepareThreshold(0);
                    ps.setArray(1, c.createArrayOf("text", canonicalKeys.toArray()));
                    try (ResultSet rs = ps.executeQuery()) {
                        Map<String, ProjectionDocument> byKey = new LinkedHashMap<>();
                        while (rs.next()) {
                            ProjectionDocument doc = rowToDocument(rs, manifest.returnableFields(),
                                ctx.resolutionMode(), ctx.matchColumns(), ctx.keyCol());
                            byKey.put(doc.id(), doc);
                        }
                        return byKey;
                    }
                } catch (SQLException e) {
                    throw explainUnreadableView(tenantId, entity, ctx.keyCol(), e);
                }
            }
        }));
    }

    // -------------------------------------------------------------------------------------------
    // §5d: purge-drain.
    // -------------------------------------------------------------------------------------------

    /**
     * Drains {@code core.search_index_purge} directly, independent of which tenants currently hold a
     * {@code valid} {@code core.search_index} row — a tenant whose last index was just deleted has
     * none, and must not be skipped for that reason (design doc D-purge-drain). Each row is its own
     * independent, lockable unit of work: a tenant-delete's fan-out routinely leaves many purge rows
     * for one tenant in one tick, and nothing here assumes or requires they arrive together, or that
     * one row's skip/failure affects any other.
     */
    void purgeDrain() {
        Optional<ProjectionSink> sink = gate.resolve();
        if (sink.isEmpty()) return; // inert without a provider, same as discoveryTick

        for (PurgeRow row : fetchUndrainedPurgeRows()) {
            Optional<RunLock> lockOpt = tryLock("purgeDrain", row.tenantId(), row.name());
            if (lockOpt.isEmpty()) continue; // could not attempt it, or another unit of work already owns this pair
            RunLock lock = lockOpt.get();

            boolean reappearedDuringPurge = false;
            try (lock) {
                // core.search_index_purge only ever gains a row via a CTE that DELETEs the matching
                // core.search_index row in the same statement (SearchIndexStore#deleteWithPurge /
                // purgeAllForEntity / purgeAllForTenant) — so a row existing again for this exact
                // (tenant, name) NOW means it was reapplied after this purge was queued, and this
                // request is superseded: the reapply's own rebuild fully replaces whatever the sink
                // still holds via ProjectionSink#publish's atomic generation swap. Destructively purging
                // here would instead wipe the reapplied index. Just retire the stale request.
                if (readDeclaration(row.tenantId(), row.name()).isPresent()) {
                    deletePurgeRow(row.id());
                    continue;
                }
                sink.get().purgeIndex(row.tenantId(), row.name());
                markStore.deleteAllFor(row.tenantId(), row.name());
                stateStore.delete(row.tenantId(), row.name());
                deletePurgeRow(row.id());
                afterPurgeAction.accept(row.tenantId(), row.name());
                // Narrow, not close: a reapply landing in the exact window between the check above and
                // the destructive actions just taken (code review, #671) is not serialized against this
                // RunLock — the REST-driven reapply path takes only EntityDefinitionLock, keyed by
                // entity, not by this (tenant, name) pair. A declaration existing again HERE means that
                // race just happened; the ordinary next discoveryTick would eventually notice the
                // deleted state row and rebuild anyway (a temporary empty-index window, never a
                // permanent loss), but rebuilding right away — once this lock is released below —
                // shrinks that window from up to one scheduler tick to effectively nothing.
                reappearedDuringPurge = readDeclaration(row.tenantId(), row.name()).isPresent();
            } catch (Exception e) {
                LOG.warnf(e, "SearchProjectionWorker.purgeDrain: failed for (%d, %s) — retried next tick",
                    row.tenantId(), row.name());
                // Starvation guard (code review, #671): demotes this row behind every not-yet-attempted
                // one in the next fetchUndrainedPurgeRows() ordering, so a row that fails every single
                // tick (a ProjectionSink outage, a poisoned entity) cannot permanently occupy the front
                // of the FIFO queue and starve healthy rows behind it once the backlog exceeds
                // PURGE_BATCH_SIZE. Best-effort: a failure touching even this bookkeeping just means
                // the row keeps its current position, no worse than before this guard existed.
                touchLastAttempted(row.id());
                continue;
            }
            if (reappearedDuringPurge) {
                // A fresh, separate call, never from inside the lock still held above: RunLock.tryAcquire
                // always opens a brand-new session, which would see this very call's own (just-released)
                // lock as free by the time we get here — never nested, same discipline as catchUp's own
                // dispatch()-driven rebuild.
                rebuild(row.tenantId(), row.name());
            }
        }
    }

    private record PurgeRow(long id, long tenantId, String name) {}

    /** Bounded like {@link #CATCHUP_BATCH_SIZE}: a bulk entity/tenant delete routinely leaves many
     *  purge rows behind in one go, and this tick's own row-by-row work must not load an unbounded
     *  backlog into memory. Rows left over (deleted rows never match this SELECT again) drain over
     *  however many more ticks it takes. Package-private and mutable, not a {@code static final}
     *  constant: {@code SearchProjectionPurgeDrainIT} shrinks it to reproduce a batch-overflow
     *  starvation scenario without needing hundreds of real rows. */
    int purgeBatchSize = 500;

    private List<PurgeRow> fetchUndrainedPurgeRows() {
        return Jdbc.query(adminDs, "SearchProjectionWorker.fetchUndrainedPurgeRows",
            "SELECT id, tenant_id, name FROM core.search_index_purge "
          + "ORDER BY last_attempted_at NULLS FIRST, id LIMIT " + purgeBatchSize,
            ps -> {},
            rs -> new PurgeRow(rs.getLong(1), rs.getLong(2), rs.getString(3)));
    }

    private void touchLastAttempted(long id) {
        Jdbc.update(adminDs, "SearchProjectionWorker.touchLastAttempted",
            "UPDATE core.search_index_purge SET last_attempted_at = now() WHERE id = ?",
            ps -> ps.setLong(1, id));
    }

    private void deletePurgeRow(long id) {
        Jdbc.update(adminDs, "SearchProjectionWorker.deletePurgeRow",
            "DELETE FROM core.search_index_purge WHERE id = ?", ps -> ps.setLong(1, id));
    }
}
