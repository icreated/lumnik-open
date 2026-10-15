package io.lumnik.hub.search.projection;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.search.ProjectionSink;
import io.lumnik.hub.search.ProjectionSinkGate;
import io.lumnik.hub.search.SearchIndexManifest;
import io.lumnik.hub.search.mark.SearchProjectionMarkStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Design doc §5b step 6: a rebuild that fails mid-scan calls abandon, never publish; state ends
 * degraded with the live generation (if any) unchanged. Exercised here at the unit level against a
 * ProjectionSink whose upsert throws — the real scan/cursor mechanics are exercised by the
 * {@code @QuarkusTest} IT ({@code SearchProjectionFullRebuildIT}) since they need a real Postgres
 * connection and view.
 *
 * <p>Calls {@link SearchProjectionWorker#runBuild} directly rather than the 3-arg
 * {@link SearchProjectionWorker#rebuild} entry point: {@code rebuild} additionally claims a real
 * {@code RunLock} and re-reads {@code core.search_index} on the admin datasource, both genuinely
 * Postgres-backed and out of scope for a database-free unit test — {@code runBuild} is the exact
 * same §5b steps 2-6 logic, given an already-read declaration and watermark.
 */
class SearchProjectionWorkerRebuildTest {

    @Test
    void failedRebuildAbandonsAndNeverPublishes() {
        ProjectionSinkGate gate = mock(ProjectionSinkGate.class);
        SearchProjectionMarkStore markStore = mock(SearchProjectionMarkStore.class);
        SearchProjectionStateStore stateStore = mock(SearchProjectionStateStore.class);
        ProjectionSink sink = mock(ProjectionSink.class);

        when(gate.resolve()).thenReturn(Optional.of(sink));
        when(sink.beginGeneration(anyLong(), anyString(), any())).thenReturn("gen-1");
        doThrow(new RuntimeException("boom mid-scan")).when(sink)
            .upsert(anyLong(), anyString(), anyString(), anyList());

        SearchProjectionWorker worker = new SearchProjectionWorker(gate, markStore, stateStore);
        worker.metrics = new SearchProjectionMetrics();
        worker.metrics.registry = new SimpleMeterRegistry();
        // The test seam (Step 2): a fake row source that drives sink.upsert (whose mock stub above
        // throws) instead of a real JDBC ResultSet.
        worker.rowScanner = (tenantId, index, entity, manifest, s, gen, runId) ->
            s.upsert(tenantId, index, gen, List.of());

        SearchIndexManifest manifest = new SearchIndexManifest("idx", "widget", "simple",
            List.of(new SearchIndexManifest.Field("name", SearchIndexManifest.Match.TEXT, false)));
        SearchProjectionWorker.SearchIndexDeclaration decl =
            new SearchProjectionWorker.SearchIndexDeclaration("valid", "widget", manifest);

        worker.runBuild(1L, "idx", decl, List.of(42L), null, new SearchProjectionWorker.ClaimedRun(UUID.randomUUID(), Instant.now()));

        verify(sink).beginGeneration(eq(1L), eq("idx"), any());
        verify(sink).abandon(1L, "idx", "gen-1");
        verify(sink, never()).publish(anyLong(), anyString(), anyString(), any());
        verify(stateStore).markDegradedEndingRun(eq(1L), eq("idx"), anyString(), any());
        verify(stateStore, never()).markReady(anyLong(), anyString(), anyString(), anyString(), anyLong());
        verify(stateStore, never()).markCancelled(anyLong(), anyString(), any(), anyBoolean());
        verifyNoInteractions(markStore); // no cleanup of any kind on a failed build
    }

    @Test
    void betweenBatchCancellationAbandonsAndRecordsCancelledNeverPublishes() {
        ProjectionSinkGate gate = mock(ProjectionSinkGate.class);
        SearchProjectionMarkStore markStore = mock(SearchProjectionMarkStore.class);
        SearchProjectionStateStore stateStore = mock(SearchProjectionStateStore.class);
        ProjectionSink sink = mock(ProjectionSink.class);
        UUID runId = UUID.randomUUID();

        when(gate.resolve()).thenReturn(Optional.of(sink));
        when(sink.beginGeneration(anyLong(), anyString(), any())).thenReturn("gen-1");
        when(stateStore.find(1L, "idx")).thenReturn(Optional.of(
            new SearchProjectionState(1L, "idx", "postgres", null, 0, "building", 0, null))); // no prior live_generation

        SearchProjectionWorker worker = new SearchProjectionWorker(gate, markStore, stateStore);
        worker.metrics = new SearchProjectionMetrics();
        worker.metrics.registry = new SimpleMeterRegistry();
        worker.rowScanner = (tenantId, index, entity, manifest, s, gen, rid) -> { throw new RebuildCancelledException(); };

        SearchIndexManifest manifest = new SearchIndexManifest("idx", "widget", "simple",
            List.of(new SearchIndexManifest.Field("name", SearchIndexManifest.Match.TEXT, false)));
        SearchProjectionWorker.SearchIndexDeclaration decl =
            new SearchProjectionWorker.SearchIndexDeclaration("valid", "widget", manifest);

        worker.runBuild(1L, "idx", decl, List.of(42L), null, new SearchProjectionWorker.ClaimedRun(runId, Instant.now()));

        verify(sink).abandon(1L, "idx", "gen-1");
        verify(sink, never()).publish(anyLong(), anyString(), anyString(), any());
        verify(stateStore).markCancelled(1L, "idx", runId, false); // no prior live_generation -> status stays 'cancelled'
        verify(stateStore, never()).markDegradedEndingRun(anyLong(), anyString(), anyString(), any());
        assertThat(worker.metrics.registry.counter("lumnik_search_rebuild_total", "outcome", "cancelled").count())
            .isEqualTo(1.0); // spec test 14: the terminal-write path also records the metric
    }

    @Test
    void betweenBatchCancellationWithAPriorLiveGenerationRevertsToReady() {
        ProjectionSinkGate gate = mock(ProjectionSinkGate.class);
        SearchProjectionMarkStore markStore = mock(SearchProjectionMarkStore.class);
        SearchProjectionStateStore stateStore = mock(SearchProjectionStateStore.class);
        ProjectionSink sink = mock(ProjectionSink.class);
        UUID runId = UUID.randomUUID();

        when(gate.resolve()).thenReturn(Optional.of(sink));
        when(sink.beginGeneration(anyLong(), anyString(), any())).thenReturn("gen-2");
        when(stateStore.find(1L, "idx")).thenReturn(Optional.of(
            new SearchProjectionState(1L, "idx", "postgres", "gen-1", 0, "building", 0, null))); // prior live_generation

        SearchProjectionWorker worker = new SearchProjectionWorker(gate, markStore, stateStore);
        worker.metrics = new SearchProjectionMetrics();
        worker.metrics.registry = new SimpleMeterRegistry();
        worker.rowScanner = (tenantId, index, entity, manifest, s, gen, rid) -> { throw new RebuildCancelledException(); };

        SearchIndexManifest manifest = new SearchIndexManifest("idx", "widget", "simple",
            List.of(new SearchIndexManifest.Field("name", SearchIndexManifest.Match.TEXT, false)));
        SearchProjectionWorker.SearchIndexDeclaration decl =
            new SearchProjectionWorker.SearchIndexDeclaration("valid", "widget", manifest);

        worker.runBuild(1L, "idx", decl, List.of(42L), null, new SearchProjectionWorker.ClaimedRun(runId, Instant.now()));

        verify(stateStore).markCancelled(1L, "idx", runId, true); // reverts to 'ready', still stamps outcome 'cancelled'
        assertThat(worker.metrics.registry.counter("lumnik_search_rebuild_total", "outcome", "cancelled").count())
            .isEqualTo(1.0);
    }

    @Test
    void prePublishCasLosingTheRaceAbandonsAndRecordsCancelledNeverPublishes() {
        ProjectionSinkGate gate = mock(ProjectionSinkGate.class);
        SearchProjectionMarkStore markStore = mock(SearchProjectionMarkStore.class);
        SearchProjectionStateStore stateStore = mock(SearchProjectionStateStore.class);
        ProjectionSink sink = mock(ProjectionSink.class);
        UUID runId = UUID.randomUUID();

        when(gate.resolve()).thenReturn(Optional.of(sink));
        when(sink.beginGeneration(anyLong(), anyString(), any())).thenReturn("gen-1");
        when(stateStore.casToPublishing(1L, "idx", runId)).thenReturn(false); // a cancel won the race
        when(stateStore.find(1L, "idx")).thenReturn(Optional.of(
            new SearchProjectionState(1L, "idx", "postgres", null, 0, "building", 0, null)));

        SearchProjectionWorker worker = new SearchProjectionWorker(gate, markStore, stateStore);
        worker.metrics = new SearchProjectionMetrics();
        worker.metrics.registry = new SimpleMeterRegistry();
        worker.rowScanner = (tenantId, index, entity, manifest, s, gen, rid) -> s.upsert(tenantId, index, gen, List.of());

        SearchIndexManifest manifest = new SearchIndexManifest("idx", "widget", "simple",
            List.of(new SearchIndexManifest.Field("name", SearchIndexManifest.Match.TEXT, false)));
        SearchProjectionWorker.SearchIndexDeclaration decl =
            new SearchProjectionWorker.SearchIndexDeclaration("valid", "widget", manifest);

        worker.runBuild(1L, "idx", decl, List.of(42L), null, new SearchProjectionWorker.ClaimedRun(runId, Instant.now()));

        verify(sink).abandon(1L, "idx", "gen-1");
        verify(sink, never()).publish(anyLong(), anyString(), anyString(), any());
        verify(stateStore).markCancelled(1L, "idx", runId, false);
        assertThat(worker.metrics.registry.counter("lumnik_search_rebuild_total", "outcome", "cancelled").count())
            .isEqualTo(1.0);
    }

    @Test
    void prePublishCasWinningTheRaceProceedsToPublishAndRecordsReady() throws SQLException {
        ProjectionSinkGate gate = mock(ProjectionSinkGate.class);
        SearchProjectionMarkStore markStore = mock(SearchProjectionMarkStore.class);
        SearchProjectionStateStore stateStore = mock(SearchProjectionStateStore.class);
        ProjectionSink sink = mock(ProjectionSink.class);
        UUID runId = UUID.randomUUID();

        when(gate.resolve()).thenReturn(Optional.of(sink));
        when(sink.name()).thenReturn("postgres");
        when(sink.beginGeneration(anyLong(), anyString(), any())).thenReturn("gen-1");
        when(stateStore.casToPublishing(1L, "idx", runId)).thenReturn(true); // this run wins

        SearchProjectionWorker worker = new SearchProjectionWorker(gate, markStore, stateStore);
        worker.metrics = new SearchProjectionMetrics();
        worker.metrics.registry = new SimpleMeterRegistry();
        worker.rowScanner = (tenantId, index, entity, manifest, s, gen, rid) -> s.upsert(tenantId, index, gen, List.of());
        // commitMarkCleanupAndMarkReady (unchanged by this task) needs a Connection it can open,
        // commit and restore autoCommit on — markStore/stateStore never touch it themselves (both
        // mocked), so a bare mock Connection is enough to let the untouched tail reach 'ready'.
        worker.adminDs = mock(AgroalDataSource.class);
        when(worker.adminDs.getConnection()).thenReturn(mock(Connection.class));

        SearchIndexManifest manifest = new SearchIndexManifest("idx", "widget", "simple",
            List.of(new SearchIndexManifest.Field("name", SearchIndexManifest.Match.TEXT, false)));
        SearchProjectionWorker.SearchIndexDeclaration decl =
            new SearchProjectionWorker.SearchIndexDeclaration("valid", "widget", manifest);

        worker.runBuild(1L, "idx", decl, List.of(42L), null, new SearchProjectionWorker.ClaimedRun(runId, Instant.now()));

        verify(sink).publish(1L, "idx", "gen-1", null);
        verify(stateStore, never()).markCancelled(anyLong(), anyString(), any(), anyBoolean());
        assertThat(worker.metrics.registry.counter("lumnik_search_rebuild_total", "outcome", "ready").count())
            .isEqualTo(1.0);
    }

    /**
     * Code-review finding: {@code sink.publish} throwing used to fall into the same catch as
     * {@code beginGeneration}/{@code scan} failing, which abandons {@code gen} — but "publish is
     * atomic" is the SPI's own guarantee that the SWITCH is all-or-nothing at the provider, never a
     * promise that a caller who sees the call throw can tell a lost commit from a real rollback (an
     * HTTP provider's response dropping after it already committed server-side is the ordinary shape
     * of this). Proves {@code publish} throwing now takes the SAME conservative path as a failure
     * strictly after it succeeds ({@code markDegradedClearingGeneration}, never {@code abandon}) —
     * exactly {@code SearchProjectionRunReaper.reapPublishing}'s own posture for a crash in this same
     * phase.
     */
    @Test
    void publishThrowingNeverAbandonsAndMarksDegradedClearingGeneration() {
        ProjectionSinkGate gate = mock(ProjectionSinkGate.class);
        SearchProjectionMarkStore markStore = mock(SearchProjectionMarkStore.class);
        SearchProjectionStateStore stateStore = mock(SearchProjectionStateStore.class);
        ProjectionSink sink = mock(ProjectionSink.class);
        UUID runId = UUID.randomUUID();

        when(gate.resolve()).thenReturn(Optional.of(sink));
        when(sink.beginGeneration(anyLong(), anyString(), any())).thenReturn("gen-1");
        when(stateStore.casToPublishing(1L, "idx", runId)).thenReturn(true); // this run wins the CAS
        doThrow(new RuntimeException("connection reset after the provider may have already committed"))
            .when(sink).publish(1L, "idx", "gen-1", null);

        SearchProjectionWorker worker = new SearchProjectionWorker(gate, markStore, stateStore);
        worker.metrics = new SearchProjectionMetrics();
        worker.metrics.registry = new SimpleMeterRegistry();
        worker.rowScanner = (tenantId, index, entity, manifest, s, gen, rid) -> s.upsert(tenantId, index, gen, List.of());

        SearchIndexManifest manifest = new SearchIndexManifest("idx", "widget", "simple",
            List.of(new SearchIndexManifest.Field("name", SearchIndexManifest.Match.TEXT, false)));
        SearchProjectionWorker.SearchIndexDeclaration decl =
            new SearchProjectionWorker.SearchIndexDeclaration("valid", "widget", manifest);

        worker.runBuild(1L, "idx", decl, List.of(42L), null, new SearchProjectionWorker.ClaimedRun(runId, Instant.now()));

        verify(sink).publish(1L, "idx", "gen-1", null);
        verify(sink, never()).abandon(anyLong(), anyString(), anyString());
        verify(stateStore).markDegradedClearingGeneration(eq(1L), eq("idx"), anyString(), eq(runId));
        verify(stateStore, never()).markDegradedEndingRun(anyLong(), anyString(), anyString(), any());
        verify(stateStore, never()).markReady(anyLong(), anyString(), anyString(), anyString(), anyLong());
        verify(stateStore, never()).markCancelled(anyLong(), anyString(), any(), anyBoolean());
        assertThat(worker.metrics.registry.counter("lumnik_search_rebuild_total", "outcome", "failed").count())
            .isEqualTo(1.0);
    }

    /**
     * Code-review finding: {@code publish}/{@code commitMarkCleanupAndMarkReady} used to run outside
     * the try/catch guarding {@code beginGeneration}/{@code scan} — an exception there propagated
     * uncaught, leaving the state row stuck in {@code building} with no {@code last_error}, until
     * {@code SearchProjectionRunReaper} eventually degraded it with a generic, factually wrong "reaped"
     * message. Proves the commit-stage failure is now caught and recorded with its real cause, and —
     * since {@code publish} already succeeded — the live generation is never abandoned over it, but
     * IS cleared from the state row (a later code-review finding: leaving the row's OLD live_generation
     * in place would let a future catch-up write against a generation the sink no longer recognizes as
     * live, once a prior successful build's own live_generation survived into this attempt).
     * {@code worker.adminDs} is deliberately left unset: {@code commitMarkCleanupAndMarkReady}'s own
     * {@code adminDs.getConnection()} then throws a NullPointerException, standing in for any real
     * failure in that transaction without needing a real Postgres connection.
     */
    @Test
    void commitStageFailureAfterPublishMarksDegradedClearingGenerationWithoutAbandoningTheLiveGeneration() {
        ProjectionSinkGate gate = mock(ProjectionSinkGate.class);
        SearchProjectionMarkStore markStore = mock(SearchProjectionMarkStore.class);
        SearchProjectionStateStore stateStore = mock(SearchProjectionStateStore.class);
        ProjectionSink sink = mock(ProjectionSink.class);

        when(gate.resolve()).thenReturn(Optional.of(sink));
        when(sink.name()).thenReturn("postgres");
        when(sink.beginGeneration(anyLong(), anyString(), any())).thenReturn("gen-1");
        when(stateStore.casToPublishing(anyLong(), anyString(), any())).thenReturn(true); // this run wins the CAS

        SearchProjectionWorker worker = new SearchProjectionWorker(gate, markStore, stateStore);
        worker.metrics = new SearchProjectionMetrics();
        worker.metrics.registry = new SimpleMeterRegistry();
        worker.rowScanner = (tenantId, index, entity, manifest, s, gen, runId) -> {}; // scan succeeds, no rows

        SearchIndexManifest manifest = new SearchIndexManifest("idx", "widget", "simple",
            List.of(new SearchIndexManifest.Field("name", SearchIndexManifest.Match.TEXT, false)));
        SearchProjectionWorker.SearchIndexDeclaration decl =
            new SearchProjectionWorker.SearchIndexDeclaration("valid", "widget", manifest);

        worker.runBuild(1L, "idx", decl, List.of(42L), null, new SearchProjectionWorker.ClaimedRun(UUID.randomUUID(), Instant.now()));

        verify(sink).publish(1L, "idx", "gen-1", null);
        verify(sink, never()).abandon(anyLong(), anyString(), anyString());
        verify(stateStore).markDegradedClearingGeneration(eq(1L), eq("idx"), anyString(), any());
        verify(stateStore, never()).markDegradedEndingRun(anyLong(), anyString(), anyString(), any());
        verify(stateStore, never()).markReady(anyLong(), anyString(), anyString(), anyString(), anyLong());
        verify(stateStore, never()).markCancelled(anyLong(), anyString(), any(), anyBoolean());
    }

    /**
     * #703 finding: {@link SearchProjectionWorker#triggerRebuild}'s own concurrency gate. With every
     * permit already held, {@code rebuildPermits.tryAcquire()} fails BEFORE {@code
     * RunLock.tryAcquire} is ever attempted — {@code worker.adminDs} is deliberately left unset (null)
     * so that, had the gate not short-circuited first, the very next line would NPE; the absence of
     * any exception here is itself the proof the lock was never touched.
     */
    @Test
    void permitExhaustionReturnsTooManyInFlightWithoutEverAttemptingTheLock() {
        ProjectionSinkGate gate = mock(ProjectionSinkGate.class);
        SearchProjectionMarkStore markStore = mock(SearchProjectionMarkStore.class);
        SearchProjectionStateStore stateStore = mock(SearchProjectionStateStore.class);

        SearchProjectionWorker worker = new SearchProjectionWorker(gate, markStore, stateStore);
        worker.metrics = new SearchProjectionMetrics();
        worker.metrics.registry = new SimpleMeterRegistry();
        worker.maxConcurrentRebuilds = 1;
        worker.rebuildPermits = new Semaphore(1);
        worker.rebuildPermits.acquireUninterruptibly(); // hold the only permit ourselves

        SearchIndexManifest manifest = new SearchIndexManifest("idx", "widget", "simple",
            List.of(new SearchIndexManifest.Field("name", SearchIndexManifest.Match.TEXT, false)));
        SearchProjectionWorker.SearchIndexDeclaration snapshot =
            new SearchProjectionWorker.SearchIndexDeclaration("valid", "widget", manifest);

        RebuildClaim claim = worker.triggerRebuild(1L, "idx", snapshot);

        assertThat(claim).isInstanceOf(RebuildClaim.TooManyInFlight.class);
        assertThat(worker.rebuildPermits.availablePermits()).isZero(); // still held by us, untouched
        verifyNoInteractions(gate, markStore, stateStore);
    }

    @Test
    void publishReceivesTheBaseCapturedBeforeTheBuild() throws SQLException {
        ProjectionSinkGate gate = mock(ProjectionSinkGate.class);
        SearchProjectionMarkStore markStore = mock(SearchProjectionMarkStore.class);
        SearchProjectionStateStore stateStore = mock(SearchProjectionStateStore.class);
        ProjectionSink sink = mock(ProjectionSink.class);
        UUID runId = UUID.randomUUID();

        when(gate.resolve()).thenReturn(Optional.of(sink));
        when(sink.name()).thenReturn("postgres");
        when(sink.beginGeneration(anyLong(), anyString(), any())).thenReturn("gen-1");
        when(stateStore.casToPublishing(1L, "idx", runId)).thenReturn(true);

        SearchProjectionWorker worker = new SearchProjectionWorker(gate, markStore, stateStore);
        worker.metrics = new SearchProjectionMetrics();
        worker.metrics.registry = new SimpleMeterRegistry();
        worker.rowScanner = (tenantId, index, entity, manifest, s, gen, rid) -> { };
        worker.adminDs = mock(AgroalDataSource.class);
        when(worker.adminDs.getConnection()).thenReturn(mock(Connection.class));

        SearchIndexManifest manifest = new SearchIndexManifest("idx", "widget", "simple",
            List.of(new SearchIndexManifest.Field("name", SearchIndexManifest.Match.TEXT, false)));
        SearchProjectionWorker.SearchIndexDeclaration decl =
            new SearchProjectionWorker.SearchIndexDeclaration("valid", "widget", manifest);
        Instant base = Instant.parse("2026-09-30T09:59:00Z");

        worker.runBuild(1L, "idx", decl, List.of(42L), base, new SearchProjectionWorker.ClaimedRun(runId, Instant.now()));

        verify(sink).publish(1L, "idx", "gen-1", base);
    }
}
