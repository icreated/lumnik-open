package io.lumnik.hub.search.projection;

import io.lumnik.extraction.spi.search.ProjectionDefinition;
import io.lumnik.extraction.spi.search.ProjectionDocument;
import io.lumnik.extraction.spi.search.ProjectionSink;
import io.lumnik.extraction.spi.search.ProviderStatus;
import io.lumnik.hub.search.ProjectionSinkGate;
import io.lumnik.hub.search.mark.SearchProjectionMarkStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * §5a's dispatch decision as executable specification (spec tests 1, 8, 9, 10), plus the two
 * complementary cases (ready-and-at-head, behind-head). This test deliberately avoids Mockito —
 * unlike some of this class's own siblings ({@code SearchProjectionWorkerRebuildTest}/{@code
 * SearchProjectionWorkerCatchupTest}), which do use it — so the worker's {@code
 * gate}/{@code markStore}/{@code stateStore} collaborators are hand-written fakes (subclasses
 * overriding the one or two methods each test needs), and {@code rebuild}/{@code catchUp} calls are
 * observed by subclassing {@link SearchProjectionWorker} itself and recording the package-private
 * stub calls instead of running them — the same seam the class's own javadoc calls out those methods
 * for. Mirrors the {@code FakeSink} style already used by {@code ProjectionSinkGateTest}.
 */
class SearchProjectionWorkerTest {

    @Test
    void noProviderResolvedIsANoOp() {
        RecordingWorker worker = new RecordingWorker(
            new FakeGate(null), new ThrowingMarkStore(), new ThrowingStateStore());

        worker.discoveryTick();

        assertThat(worker.rebuildCalls).isEmpty();
        assertThat(worker.catchUpCalls).isEmpty();
    }

    @Test
    void noStateRowDispatchesToRebuild() {
        RecordingWorker worker = workerWithOneValidIndex(1L, "idx", "widget", Optional.empty(), false);

        worker.discoveryTick();

        assertThat(worker.rebuildCalls).containsExactly(new Dispatch(1L, "idx"));
        assertThat(worker.catchUpCalls).isEmpty();
    }

    @Test
    void degradedRowFromFailedFirstAttemptDispatchesToRebuildNotSkipped() {
        // spec test 10: live_generation IS NULL, whatever status — including a failed FIRST attempt,
        // not only "no state row yet".
        SearchProjectionState degraded = new SearchProjectionState(1L, "idx", null, null, 0, "degraded", 1, "boom");
        RecordingWorker worker = workerWithOneValidIndex(1L, "idx", "widget", Optional.of(degraded), false);

        worker.discoveryTick();

        assertThat(worker.rebuildCalls).containsExactly(new Dispatch(1L, "idx"));
        assertThat(worker.catchUpCalls).isEmpty();
    }

    @Test
    void providerMismatchDispatchesToRebuildRegardlessOfPendingMarks() {
        // spec test 8: a pending mark exists, but provider_name differs — rebuild wins regardless.
        SearchProjectionState mismatched =
            new SearchProjectionState(1L, "idx", "old-provider", "gen-1", 999L, "ready", 0, null);
        RecordingWorker worker = workerWithOneValidIndex(1L, "idx", "widget", Optional.of(mismatched), true);

        worker.discoveryTick();

        assertThat(worker.rebuildCalls).containsExactly(new Dispatch(1L, "idx"));
        assertThat(worker.catchUpCalls).isEmpty();
    }

    @Test
    void readyWithNoPendingMarkDoesNothingThisTick() {
        SearchProjectionState current =
            new SearchProjectionState(1L, "idx", "postgres", "gen-1", 999L, "ready", 0, null);
        RecordingWorker worker = workerWithOneValidIndex(1L, "idx", "widget", Optional.of(current), false);

        worker.discoveryTick();

        assertThat(worker.rebuildCalls).isEmpty();
        assertThat(worker.catchUpCalls).isEmpty();
    }

    /**
     * #712: a pending mark alone triggers catch-up — never a comparison between {@code position}
     * and the mark table's head id. A state row whose {@code position} already equals a STALE head
     * value (as it would right after a lower-id mark's commit lands late) must still dispatch to
     * catch-up as long as the mark table has anything left for this pair.
     */
    @Test
    void pendingMarkDispatchesToCatchUpRegardlessOfPosition() {
        SearchProjectionState current =
            new SearchProjectionState(1L, "idx", "postgres", "gen-1", 999L, "ready", 0, null);
        RecordingWorker worker = workerWithOneValidIndex(1L, "idx", "widget", Optional.of(current), true);

        worker.discoveryTick();

        assertThat(worker.catchUpCalls).containsExactly(new Dispatch(1L, "idx"));
        assertThat(worker.rebuildCalls).isEmpty();
    }

    @Test
    void onePairThrowingDoesNotAbortOtherPairsInTheSameTick() {
        // Fault isolation (Task 12a review finding): a transient DB blip surfacing from anywhere in
        // one (tenant, index) pair's own dispatch — here from deep inside rebuild()'s own Jdbc calls
        // — must not stop discoveryTick's fan-out from reaching every OTHER pair scheduled the same
        // tick. "idx2" throws; "idx1" must still be dispatched. (Both pairs' state/head lookups are
        // now batched ahead of the per-pair loop, code review #671 — a per-pair store-level throw is
        // no longer a distinct fault-injection point from a per-pair dispatch-level one, so this
        // proves the SAME try/catch around dispatch() via rebuild(), the only per-pair work left.)
        FakeGate gate = new FakeGate(new FakeSink("postgres"));
        FakeMarkStore marks = new FakeMarkStore(List.of(1L), Map.of("widget", List.of("idx1", "idx2")), false);
        FakeStateStore states = new FakeStateStore(1L, "idx1", Optional.empty());
        PartiallyThrowingWorker worker = new PartiallyThrowingWorker(gate, marks, states, "idx2");

        worker.discoveryTick();

        assertThat(worker.rebuildCalls).containsExactly(new Dispatch(1L, "idx1"));
        assertThat(worker.catchUpCalls).isEmpty();
    }

    // -- #730: the freshness advance rides the tick ------------------------------------------------

    @Test
    void aReadyPairWithNoPendingMarkTriesTheAsOfAdvanceWithItsLiveGeneration() {
        SearchProjectionState current =
            new SearchProjectionState(1L, "idx", "postgres", "gen-1", 999L, "ready", 0, null);
        RecordingWorker worker = workerWithOneValidIndex(1L, "idx", "widget", Optional.of(current), false);
        worker.advancer = mock(SearchProjectionAsOfAdvancer.class);

        worker.discoveryTick();

        verify(worker.advancer).advance(eq(1L), eq("idx"), any(), eq("gen-1"));
    }

    @Test
    void theAdvanceFollowsASuccessfulCatchUp() {
        SearchProjectionState current =
            new SearchProjectionState(1L, "idx", "postgres", "gen-1", 999L, "ready", 0, null);
        RecordingWorker worker = workerWithOneValidIndex(1L, "idx", "widget", Optional.of(current), true);
        worker.advancer = mock(SearchProjectionAsOfAdvancer.class);

        worker.discoveryTick();

        assertThat(worker.catchUpCalls).containsExactly(new Dispatch(1L, "idx"));
        verify(worker.advancer).advance(eq(1L), eq("idx"), any(), eq("gen-1"));
    }

    @Test
    void aDegradedPairDoesNotAdvance() {
        SearchProjectionState degraded =
            new SearchProjectionState(1L, "idx", "postgres", "gen-1", 999L, "degraded", 1, "boom");
        RecordingWorker worker = workerWithOneValidIndex(1L, "idx", "widget", Optional.of(degraded), false);
        worker.advancer = mock(SearchProjectionAsOfAdvancer.class);

        worker.discoveryTick();

        verify(worker.advancer, never()).advance(anyLong(), anyString(), any(), anyString());
    }

    @Test
    void aCatchUpThatNeedsAFullRebuildDoesNotAdvance() {
        SearchProjectionState current =
            new SearchProjectionState(1L, "idx", "postgres", "gen-1", 999L, "ready", 0, null);
        RecordingWorker worker = workerWithOneValidIndex(1L, "idx", "widget", Optional.of(current), true);
        worker.catchUpNeedsRebuild = true;
        worker.advancer = mock(SearchProjectionAsOfAdvancer.class);

        worker.discoveryTick();

        assertThat(worker.rebuildCalls).containsExactly(new Dispatch(1L, "idx"));
        verify(worker.advancer, never()).advance(anyLong(), anyString(), any(), anyString());
    }

    @Test
    void anAdvanceFailureNeverPropagatesOutOfTheTick() {
        SearchProjectionState current =
            new SearchProjectionState(1L, "idx", "postgres", "gen-1", 999L, "ready", 0, null);
        RecordingWorker worker = workerWithOneValidIndex(1L, "idx", "widget", Optional.of(current), false);
        worker.advancer = mock(SearchProjectionAsOfAdvancer.class);
        when(worker.advancer.advance(anyLong(), anyString(), any(), anyString()))
            .thenThrow(new IllegalStateException("db blip"));

        worker.discoveryTick(); // must not throw

        verify(worker.advancer).advance(eq(1L), eq("idx"), any(), eq("gen-1"));
    }

    // -- #754: bounded catch-up with a global time budget -------------------------------------------

    private static final long SEC = 1_000_000_000L;

    private static SearchProjectionState ready(String index) {
        return new SearchProjectionState(1L, index, "postgres", "gen-1", 999L, "ready", 0, null);
    }

    /** Two ready pairs "a" and "b" with pending marks, each scripted, and the given budget. */
    private RecordingWorker twoPairs(Duration budget, List<CatchUpResult> a, List<CatchUpResult> b) {
        FakeMarkStore marks = new FakeMarkStore(List.of(1L), Map.of("widget", List.of("a", "b")), true);
        FakeStateStore states = new FakeStateStore(1L, Map.of("a", ready("a"), "b", ready("b")));
        RecordingWorker worker = new RecordingWorker(new FakeGate(new FakeSink("postgres")), marks, states);
        worker.catchupBudget = budget;
        worker.script.put("a", new ArrayDeque<>(a));
        worker.script.put("b", new ArrayDeque<>(b));
        return worker;
    }

    private static List<String> order(RecordingWorker w) {
        return w.catchUpCalls.stream().map(Dispatch::index).toList();
    }

    private static final CatchUpResult P = CatchUpResult.PROGRESSED;
    private static final CatchUpResult IDLE = CatchUpResult.IDLE;

    @Test
    void threeBatchesDrainInOneTickWithAnAmpleBudget() {
        RecordingWorker worker = workerWithOneValidIndex(1L, "a", "widget", Optional.of(ready("a")), true);
        worker.catchupBudget = Duration.ofSeconds(30);
        worker.script.put("a", new ArrayDeque<>(List.of(P, P, P, IDLE)));

        worker.discoveryTick();

        assertThat(order(worker)).containsExactly("a", "a", "a", "a"); // three batches, then the IDLE probe
    }

    @Test
    void twoPairsAlternateRoundRobin() {
        RecordingWorker worker = twoPairs(Duration.ofSeconds(30), List.of(P, P, P, IDLE), List.of(P, P, IDLE));

        worker.discoveryTick();

        assertThat(order(worker)).containsExactly("a", "b", "a", "b", "a", "b", "a");
    }

    @Test
    void aFailedPairIsNotRetriedInTheTickWhileTheOthersContinue() {
        RecordingWorker worker = twoPairs(Duration.ofSeconds(30), List.of(CatchUpResult.FAILED), List.of(P, P, IDLE));

        worker.discoveryTick();

        assertThat(order(worker)).containsExactly("a", "b", "b", "b");
    }

    @Test
    void aZeroBudgetKeepsExactlyOneAttemptPerPair() {
        RecordingWorker worker = twoPairs(Duration.ZERO, List.of(P, P, P), List.of(P, P));

        worker.discoveryTick();

        assertThat(order(worker)).containsExactly("a", "b");
    }

    @Test
    void anExpiredBudgetDoesNotStartTheNextBatch() {
        RecordingWorker worker = twoPairs(Duration.ofSeconds(30), List.of(P, P, P, P), List.of(P, P, P, P));
        for (int i = 0; i < 8; i++) worker.costsNanos.add(10 * SEC);

        worker.discoveryTick();

        // round 1: a (0-10 s), b (10-20 s); extra: a starts at 20 s < 30 (ends 30 s); b would start at 30 s: not started
        assertThat(order(worker)).containsExactly("a", "b", "a");
    }

    @Test
    void theFirstRoundIsNeverGatedByTheBudget() {
        RecordingWorker worker = twoPairs(Duration.ofSeconds(30), List.of(P, P), List.of(P, P));
        worker.costsNanos.add(40 * SEC); // the first attempt alone overruns the whole budget

        worker.discoveryTick();

        assertThat(order(worker)).containsExactly("a", "b");
    }

    @Test
    void aBatchThatOverrunsTheDeadlineCompletesAndNothingFurtherStarts() {
        RecordingWorker worker = workerWithOneValidIndex(1L, "a", "widget", Optional.of(ready("a")), true);
        worker.catchupBudget = Duration.ofSeconds(30);
        worker.script.put("a", new ArrayDeque<>(List.of(P, P, P, P)));
        worker.costsNanos.add(29_900_000_000L); // first attempt ends at 29.9 s
        worker.costsNanos.add(5 * SEC);          // the extra batch starts at 29.9 s and ends at 34.9 s

        worker.discoveryTick(); // must not throw

        assertThat(order(worker)).containsExactly("a", "a");
    }

    @Test
    void aPairThatNeedsAFullRebuildInAnExtraRoundIsRebuiltAndLeavesTheRotation() {
        RecordingWorker worker = twoPairs(Duration.ofSeconds(30), List.of(P, CatchUpResult.REBUILD, P), List.of(P, IDLE));

        worker.discoveryTick();

        assertThat(worker.rebuildCalls).containsExactly(new Dispatch(1L, "a"));
        assertThat(order(worker)).containsExactly("a", "b", "a", "b");
    }

    @Test
    void aPairThatProgressedInExtraRoundsGetsOneMoreAsOfAdvance() {
        RecordingWorker worker = workerWithOneValidIndex(1L, "a", "widget", Optional.of(ready("a")), true);
        worker.catchupBudget = Duration.ofSeconds(30);
        worker.script.put("a", new ArrayDeque<>(List.of(P, P, IDLE)));
        worker.advancer = mock(SearchProjectionAsOfAdvancer.class);

        worker.discoveryTick();

        verify(worker.advancer, times(2)).advance(eq(1L), eq("a"), any(), eq("gen-1")); // after round 1, and on leaving
    }

    @Test
    void aNegativeBudgetIsRefusedAtStartup() {
        RecordingWorker worker = workerWithOneValidIndex(1L, "a", "widget", Optional.empty(), false);
        worker.catchupBudget = Duration.ofSeconds(-1);

        assertThatThrownBy(worker::initExecutor)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("catchup-budget");
    }

    // -- #894: a backlog too large to drain is rebuilt ----------------------------------------------

    /** A ready pair whose pending marks number {@code backlog}, under the given threshold. */
    private RecordingWorker readyPairWithBacklog(long backlog, int threshold) {
        FakeMarkStore marks = new FakeMarkStore(List.of(1L), Map.of("widget", List.of("idx")), true);
        marks.backlog = backlog;
        RecordingWorker worker = new RecordingWorker(new FakeGate(new FakeSink("postgres")), marks,
            new FakeStateStore(1L, "idx", Optional.of(ready("idx"))));
        worker.rebuildBacklogThreshold = threshold;
        return worker;
    }

    @Test
    void aBacklogAboveTheThresholdIsRebuiltInsteadOfCaughtUp() {
        RecordingWorker worker = readyPairWithBacklog(5_001, 5_000);
        worker.advancer = mock(SearchProjectionAsOfAdvancer.class);

        worker.discoveryTick();

        assertThat(worker.rebuildCalls).containsExactly(new Dispatch(1L, "idx"));
        assertThat(worker.catchUpCalls).isEmpty();
        verify(worker.advancer, never()).advance(anyLong(), anyString(), any(), anyString()); // the rebuild re-bases asOf
    }

    @Test
    void aBacklogAtTheThresholdIsStillCaughtUp() {
        RecordingWorker worker = readyPairWithBacklog(5_000, 5_000);

        worker.discoveryTick();

        assertThat(worker.catchUpCalls).containsExactly(new Dispatch(1L, "idx"));
        assertThat(worker.rebuildCalls).isEmpty();
    }

    /** A rebuild that failed leaves the pair degraded with its live generation: retrying it every tick would
     *  replace batches that can progress with a rebuild that may never succeed. */
    @Test
    void aDegradedPairDrainsItsBacklogBatchByBatch() {
        FakeMarkStore marks = new FakeMarkStore(List.of(1L), Map.of("widget", List.of("idx")), true);
        marks.backlog = 5_001;
        SearchProjectionState degraded =
            new SearchProjectionState(1L, "idx", "postgres", "gen-1", 999L, "degraded", 1, "scan timed out");
        RecordingWorker worker = new RecordingWorker(new FakeGate(new FakeSink("postgres")), marks,
            new FakeStateStore(1L, "idx", Optional.of(degraded)));
        worker.rebuildBacklogThreshold = 5_000;

        worker.discoveryTick();

        assertThat(worker.catchUpCalls).containsExactly(new Dispatch(1L, "idx"));
        assertThat(worker.rebuildCalls).isEmpty();
    }

    @Test
    void aZeroThresholdNeverRebuildsForABacklog() {
        RecordingWorker worker = readyPairWithBacklog(1_000_000, 0);

        worker.discoveryTick();

        assertThat(worker.catchUpCalls).containsExactly(new Dispatch(1L, "idx"));
        assertThat(worker.rebuildCalls).isEmpty();
    }

    @Test
    void aNegativeBacklogThresholdIsRefusedAtStartup() {
        RecordingWorker worker = workerWithOneValidIndex(1L, "a", "widget", Optional.empty(), false);
        worker.rebuildBacklogThreshold = -1;

        assertThatThrownBy(worker::initExecutor)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("rebuild-backlog-threshold");
    }

    private RecordingWorker workerWithOneValidIndex(long tenantId, String index, String entity,
                                                     Optional<SearchProjectionState> state, boolean hasPendingMark) {
        FakeGate gate = new FakeGate(new FakeSink("postgres"));
        FakeMarkStore marks = new FakeMarkStore(List.of(tenantId), Map.of(entity, List.of(index)), hasPendingMark);
        FakeStateStore states = new FakeStateStore(tenantId, index, state);
        return new RecordingWorker(gate, marks, states);
    }

    private record Dispatch(long tenantId, String index) {}

    /** Records rebuild/catchUp calls instead of running the stub bodies — the seam this class's own
     *  javadoc names those package-private methods for. */
    private static final class RecordingWorker extends SearchProjectionWorker {
        final List<Dispatch> rebuildCalls = new ArrayList<>();
        final List<Dispatch> catchUpCalls = new ArrayList<>();

        RecordingWorker(ProjectionSinkGate gate, SearchProjectionMarkStore markStore, SearchProjectionStateStore stateStore) {
            super(gate, markStore, stateStore);
            this.metrics = new SearchProjectionMetrics();
            this.metrics.registry = new SimpleMeterRegistry();
        }

        @Override void rebuild(long tenantId, String index) {
            rebuildCalls.add(new Dispatch(tenantId, index));
        }

        boolean catchUpNeedsRebuild = false;

        /** #754: per-index scripted results (an exhausted script answers IDLE) and a fake monotone clock that
         *  each catch-up call advances by the next entry of {@link #costsNanos} (0 once exhausted). */
        final Map<String, Deque<CatchUpResult>> script = new LinkedHashMap<>();
        final Deque<Long> costsNanos = new ArrayDeque<>();
        final long[] now = {0L};
        { nanoClock = () -> now[0]; }

        @Override CatchUpResult catchUp(long tenantId, String index) {
            catchUpCalls.add(new Dispatch(tenantId, index));
            now[0] += costsNanos.isEmpty() ? 0L : costsNanos.poll();
            Deque<CatchUpResult> scripted = script.get(index);
            if (scripted != null) return scripted.isEmpty() ? CatchUpResult.IDLE : scripted.poll();
            return catchUpNeedsRebuild ? CatchUpResult.REBUILD : CatchUpResult.IDLE; // the "needs full rebuild" signal itself is exercised by
                                        // SearchProjectionWorkerCatchupTest; here it only gates the asOf advance
        }
    }

    private static final class FakeGate extends ProjectionSinkGate {
        private final Optional<ProjectionSink> result;
        FakeGate(ProjectionSink sink) { this.result = Optional.ofNullable(sink); }
        @Override public Optional<ProjectionSink> resolve() { return result; }
    }

    private static final class FakeMarkStore extends SearchProjectionMarkStore {
        private final List<Long> tenants;
        private final Map<String, List<String>> byEntity;
        private final boolean hasPendingMark;
        FakeMarkStore(List<Long> tenants, Map<String, List<String>> byEntity, boolean hasPendingMark) {
            this.tenants = tenants;
            this.byEntity = byEntity;
            this.hasPendingMark = hasPendingMark;
        }
        @Override public Map<Long, Map<String, List<String>>> validIndexNamesByEntityAndTenantAdmin() {
            Map<Long, Map<String, List<String>>> byTenant = new LinkedHashMap<>();
            for (Long tenantId : tenants) byTenant.put(tenantId, byEntity);
            return byTenant;
        }
        @Override public Map<Long, Set<String>> pendingMarkPairsAdmin() {
            Map<Long, Set<String>> byTenant = new LinkedHashMap<>();
            if (!hasPendingMark) return byTenant;
            for (Long tenantId : tenants) {
                Set<String> pending = new LinkedHashSet<>();
                for (List<String> indexes : byEntity.values()) pending.addAll(indexes);
                byTenant.put(tenantId, pending);
            }
            return byTenant;
        }
        // #703's own gauge computation, added at the tail of discoveryTick() — this fake never sets
        // up a lag scenario of its own, so an empty map (no pending marks, zero lag) is correct here.
        @Override public Map<Long, Map<String, Instant>> oldestPendingMarkedAtAdmin() {
            return Map.of();
        }
        /** #894: how many marks every pending pair holds. */
        long backlog;
        @Override public boolean pendingMarksExceed(long tenantId, String searchIndex, int threshold) {
            return backlog > threshold;
        }
    }

    private static final class FakeStateStore extends SearchProjectionStateStore {
        private final long tenantId;
        private final Map<String, SearchProjectionState> states;
        FakeStateStore(long tenantId, String index, Optional<SearchProjectionState> state) {
            this(tenantId, state.isEmpty() ? Map.of() : Map.of(index, state.get()));
        }
        FakeStateStore(long tenantId, Map<String, SearchProjectionState> states) {
            this.tenantId = tenantId;
            this.states = states;
        }
        @Override public Map<Long, Map<String, SearchProjectionState>> findAllAdmin() {
            return states.isEmpty() ? Map.of() : Map.of(tenantId, states);
        }
    }

    /** Fails the test loudly if {@code discoveryTick} touches the mark store when it must return
     *  before ever consulting it (no provider resolved) — the hand-written stand-in for
     *  {@code verifyNoInteractions}. */
    private static final class ThrowingMarkStore extends SearchProjectionMarkStore {
        @Override public Map<Long, Map<String, List<String>>> validIndexNamesByEntityAndTenantAdmin() {
            throw new AssertionError("markStore must not be touched when no provider is resolved");
        }
        @Override public Map<Long, Set<String>> pendingMarkPairsAdmin() {
            throw new AssertionError("markStore must not be touched when no provider is resolved");
        }
    }

    private static final class ThrowingStateStore extends SearchProjectionStateStore {
        @Override public Map<Long, Map<String, SearchProjectionState>> findAllAdmin() {
            throw new AssertionError("stateStore must not be touched when no provider is resolved");
        }
    }

    /** Records rebuild/catchUp calls like {@link RecordingWorker}, except {@code rebuild} throws for
     *  exactly one named index — standing in for a transient DB blip on one (tenant, index) pair's
     *  own dispatch-time work among several scheduled the same tick. */
    private static final class PartiallyThrowingWorker extends SearchProjectionWorker {
        final List<Dispatch> rebuildCalls = new ArrayList<>();
        final List<Dispatch> catchUpCalls = new ArrayList<>();
        private final String throwingIndex;

        PartiallyThrowingWorker(ProjectionSinkGate gate, SearchProjectionMarkStore markStore,
                                SearchProjectionStateStore stateStore, String throwingIndex) {
            super(gate, markStore, stateStore);
            this.throwingIndex = throwingIndex;
            this.metrics = new SearchProjectionMetrics();
            this.metrics.registry = new SimpleMeterRegistry();
        }

        @Override void rebuild(long tenantId, String index) {
            if (index.equals(throwingIndex)) throw new RuntimeException("boom: transient DB blip");
            rebuildCalls.add(new Dispatch(tenantId, index));
        }

        @Override CatchUpResult catchUp(long tenantId, String index) {
            catchUpCalls.add(new Dispatch(tenantId, index));
            return CatchUpResult.IDLE;
        }
    }

    private static final class FakeSink implements ProjectionSink {
        private final String name;
        FakeSink(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public ProviderStatus status() { return null; }
        @Override public String beginGeneration(long tenantId, String searchIndex, ProjectionDefinition definition) { return "gen"; }
        @Override public void upsert(long tenantId, String searchIndex, String generation, List<ProjectionDocument> documents) {}
        @Override public void delete(long tenantId, String searchIndex, String generation, List<String> ids) {}
        @Override public void publish(long tenantId, String searchIndex, String generation) {}
        @Override public void abandon(long tenantId, String searchIndex, String generation) {}
        @Override public void purgeIndex(long tenantId, String searchIndex) {}
        @Override public long liveDocumentCount(long tenantId, String searchIndex) { return 0L; }
    }
}
