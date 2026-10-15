package io.lumnik.hub.search.projection;

import io.lumnik.extraction.spi.search.ProjectionDocument;
import io.lumnik.extraction.spi.search.ProjectionSink;
import io.lumnik.hub.search.ProjectionSinkGate;
import io.lumnik.hub.search.SearchIndexManifest;
import io.lumnik.hub.search.mark.MarkKind;
import io.lumnik.hub.search.mark.MarkRow;
import io.lumnik.hub.search.mark.SearchProjectionMarkStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Design doc §5c step 2 / §5a step 4: an ALL mark in a catch-up batch must abandon the batch and
 * signal "needs full rebuild" rather than calling rebuild() in-line from inside catch-up's own
 * still-open RunLock — {@code pg_try_advisory_lock} is session-level, so a fresh {@code
 * RunLock.tryAcquire} on the SAME key (a new connection changes nothing) can never join a lock
 * already held by the same session; an in-line call would deadlock forever, every tick.
 *
 * <p>{@link SearchProjectionWorker#runCatchUp} is exercised directly (spec tests 3, 4, 6) — the
 * same seam {@code SearchProjectionWorkerRebuildTest} already established for {@code runBuild}: the
 * real RunLock/declaration-re-read shell ({@link SearchProjectionWorker#catchUp}) is genuinely
 * Postgres-backed and out of scope for a database-free unit test, exercised instead by the
 * {@code @QuarkusTest} IT. Collaborators are Mockito mocks throughout, matching this test package's
 * own {@code SearchProjectionWorkerRebuildTest} and {@code ProjectionSinkGateTest}.
 */
class SearchProjectionWorkerCatchupTest {

    private static final long TENANT_ID = 1L;
    private static final String INDEX = "idx";
    private static final String ENTITY = "widget";

    @Test
    void allMarkInBatchSignalsNeedsFullRebuildWithoutCallingRebuildDirectly() {
        ProjectionSinkGate gate = mock(ProjectionSinkGate.class);
        SearchProjectionMarkStore markStore = mock(SearchProjectionMarkStore.class);
        SearchProjectionStateStore stateStore = mock(SearchProjectionStateStore.class);

        SearchProjectionState state = new SearchProjectionState(
            TENANT_ID, INDEX, "postgres", "gen-1", 100L, "ready", 0, null);
        when(stateStore.find(TENANT_ID, INDEX)).thenReturn(Optional.of(state));
        when(markStore.fetchBatch(TENANT_ID, INDEX, SearchProjectionWorker.CATCHUP_BATCH_SIZE))
            .thenReturn(List.of(
                new MarkRow(101L, MarkKind.KEY, "key-a"),
                new MarkRow(102L, MarkKind.ALL, ""),
                new MarkRow(103L, MarkKind.KEY, "key-b")));

        SearchProjectionWorker worker = new SearchProjectionWorker(gate, markStore, stateStore);
        SearchIndexManifest manifest = new SearchIndexManifest("idx", ENTITY, "simple",
            List.of(new SearchIndexManifest.Field("name", SearchIndexManifest.Match.TEXT, false)));
        SearchProjectionWorker.SearchIndexDeclaration decl =
            new SearchProjectionWorker.SearchIndexDeclaration("valid", ENTITY, manifest);

        CatchUpResult result = worker.runCatchUp(TENANT_ID, INDEX, decl);

        assertThat(result).isEqualTo(CatchUpResult.REBUILD);
        // The batch is abandoned WHOLE the instant the ALL mark is seen — gate.resolve() is never
        // even reached, so no sink is asked for and no upsert/delete/delete-mark/advance happens.
        verifyNoInteractions(gate);
        verify(markStore, never()).deleteByExactIds(anyLong(), anyString(), anyList());
        verify(stateStore, never()).advancePosition(anyLong(), anyString(), anyLong());
        verify(stateStore, never()).markDegraded(anyLong(), anyString(), anyString());

        // The deadlock-avoidance property itself: catchUp (and therefore runCatchUp) must NEVER
        // call rebuild() directly — that decision belongs to the CALLER, once catch-up's own lock
        // is released. Proven here via the REAL, unmodified dispatch()/discoveryTick() code path —
        // never a direct call to rebuild() from this test — with catchUp() stubbed on a spy purely
        // so this test needs no real Postgres connection for RunLock (catchUp's own internals, and
        // its ALL-mark signal, are exactly what the assertions above already pin). The guarantee
        // that catchUp's OWN lock is released before dispatch's rebuild() call is structural: it
        // falls out of catchUp's `try (lock) { ...; return ...; }` shape (a try-with-resources
        // closes the lock before the method can return), which dispatch() below never bypasses —
        // it only ever sees catchUp's return VALUE, after catchUp has fully returned.
        ProjectionSink sink = mock(ProjectionSink.class);
        when(sink.name()).thenReturn("postgres");
        when(gate.resolve()).thenReturn(Optional.of(sink));
        when(markStore.validIndexNamesByEntityAndTenantAdmin())
            .thenReturn(Map.of(TENANT_ID, Map.of(ENTITY, List.of(INDEX))));
        when(stateStore.findAllAdmin()).thenReturn(Map.of(TENANT_ID, Map.of(INDEX, state)));
        when(markStore.pendingMarkPairsAdmin()).thenReturn(Map.of(TENANT_ID, Set.of(INDEX))); // pending mark -> dispatch tries catch-up
        when(markStore.oldestPendingMarkedAtAdmin()).thenReturn(Map.of()); // #703's own gauge tail — no lag scenario here

        SearchProjectionWorker spyWorker = spy(new SearchProjectionWorker(gate, markStore, stateStore));
        spyWorker.metrics = new SearchProjectionMetrics();
        spyWorker.metrics.registry = new SimpleMeterRegistry();
        doReturn(CatchUpResult.REBUILD).when(spyWorker).catchUp(TENANT_ID, INDEX);
        doNothing().when(spyWorker).rebuild(TENANT_ID, INDEX); // isolates the wiring; rebuild's
                                                                        // own body is SearchProjectionWorkerRebuildTest's job

        spyWorker.discoveryTick();

        InOrder order = inOrder(spyWorker);
        order.verify(spyWorker).catchUp(TENANT_ID, INDEX);
        order.verify(spyWorker).rebuild(TENANT_ID, INDEX); // actually attempted, not silently skipped
    }

    /** #893: the batch reaches the sink in two calls — one upsert of every row still in the view, one
     *  delete of every key gone from it — never one call per mark (a commit each on the PostgreSQL
     *  provider, a forced refresh each on OpenSearch). Every mark is then deleted by its exact id. */
    @Test
    void aBatchReachesTheSinkInOneUpsertCallAndOneDeleteCall() {
        ProjectionSinkGate gate = mock(ProjectionSinkGate.class);
        SearchProjectionMarkStore markStore = mock(SearchProjectionMarkStore.class);
        SearchProjectionStateStore stateStore = mock(SearchProjectionStateStore.class);
        ProjectionSink sink = mock(ProjectionSink.class);

        SearchProjectionState state = new SearchProjectionState(
            TENANT_ID, INDEX, "postgres", "gen-1", 100L, "ready", 0, null);
        when(stateStore.find(TENANT_ID, INDEX)).thenReturn(Optional.of(state));
        when(gate.resolve()).thenReturn(Optional.of(sink));
        when(markStore.fetchBatch(TENANT_ID, INDEX, SearchProjectionWorker.CATCHUP_BATCH_SIZE))
            .thenReturn(List.of(
                new MarkRow(101L, MarkKind.KEY, "key-1"),
                new MarkRow(102L, MarkKind.KEY, "gone-1"),
                new MarkRow(103L, MarkKind.KEY, "key-2"),
                new MarkRow(104L, MarkKind.KEY, "gone-2")));
        when(markStore.deleteByExactIds(eq(TENANT_ID), eq(INDEX), anyList())).thenReturn(104L);

        ProjectionDocument doc1 = doc("key-1");
        ProjectionDocument doc2 = doc("key-2");
        SearchProjectionWorker worker = new SearchProjectionWorker(gate, markStore, stateStore);
        worker.metrics = metrics();
        worker.rowBatchReader = (tenantId, entity, manifest, canonicalKeys) -> Map.of("key-1", doc1, "key-2", doc2);

        CatchUpResult result = worker.runCatchUp(TENANT_ID, INDEX, decl());

        assertThat(result).isEqualTo(CatchUpResult.PROGRESSED);
        verify(sink).upsert(TENANT_ID, INDEX, "gen-1", List.of(doc1, doc2));
        verify(sink).delete(TENANT_ID, INDEX, "gen-1", List.of("gone-1", "gone-2"));
        verifyNoMoreInteractions(sink);
        assertThat(deletedMarkIds(markStore)).containsExactlyInAnyOrder(101L, 102L, 103L, 104L);
        verify(stateStore).advancePosition(TENANT_ID, INDEX, 104L);
    }

    /** #937: a batched call that throws falls back to one call per mark, and a mark the provider refuses
     *  no longer stops the marks behind it: the ones that apply are deleted by their exact ids, the
     *  refused one stays for the next tick (never {@code [101,103]} widened to the batch's max), and the
     *  pair is still reported degraded with the provider's message. */
    @Test
    void aRefusedMarkStaysWhileTheMarksBehindItDrain() {
        ProjectionSinkGate gate = mock(ProjectionSinkGate.class);
        SearchProjectionMarkStore markStore = mock(SearchProjectionMarkStore.class);
        SearchProjectionStateStore stateStore = mock(SearchProjectionStateStore.class);
        ProjectionSink sink = mock(ProjectionSink.class);

        SearchProjectionState state = new SearchProjectionState(
            TENANT_ID, INDEX, "postgres", "gen-1", 100L, "ready", 0, null);
        when(stateStore.find(TENANT_ID, INDEX)).thenReturn(Optional.of(state));
        when(gate.resolve()).thenReturn(Optional.of(sink));
        when(markStore.fetchBatch(TENANT_ID, INDEX, SearchProjectionWorker.CATCHUP_BATCH_SIZE))
            .thenReturn(List.of(
                new MarkRow(101L, MarkKind.KEY, "key-1"),
                new MarkRow(102L, MarkKind.KEY, "poison"),
                new MarkRow(103L, MarkKind.KEY, "key-3")));
        when(markStore.deleteByExactIds(eq(TENANT_ID), eq(INDEX), anyList())).thenReturn(103L);

        SearchProjectionWorker worker = new SearchProjectionWorker(gate, markStore, stateStore);
        worker.metrics = metrics();
        worker.rowBatchReader = everyKeyPresent();
        refusePoison(sink);

        CatchUpResult result = worker.runCatchUp(TENANT_ID, INDEX, decl());

        assertThat(result).isEqualTo(CatchUpResult.FAILED);
        InOrder order = inOrder(sink);
        order.verify(sink).upsert(TENANT_ID, INDEX, "gen-1", List.of(doc("key-1"), doc("poison"), doc("key-3")));
        order.verify(sink).upsert(TENANT_ID, INDEX, "gen-1", List.of(doc("key-1")));
        order.verify(sink).upsert(TENANT_ID, INDEX, "gen-1", List.of(doc("poison")));
        order.verify(sink).upsert(TENANT_ID, INDEX, "gen-1", List.of(doc("key-3")));
        verifyNoMoreInteractions(sink);
        verify(markStore).deleteByExactIds(TENANT_ID, INDEX, List.of(101L, 103L));
        verify(stateStore).markDegraded(eq(TENANT_ID), eq(INDEX), contains("poison document"));
    }

    /** #937: a cluster that refuses everything is bounded, not walked mark by mark: the per-mark
     *  fallback stops after {@code catchupConsecutiveFailureLimit} failures in a row, and the marks it
     *  never reached stay untouched. */
    @Test
    void thePerMarkFallbackStopsAfterTheConsecutiveFailureLimit() {
        ProjectionSinkGate gate = mock(ProjectionSinkGate.class);
        SearchProjectionMarkStore markStore = mock(SearchProjectionMarkStore.class);
        SearchProjectionStateStore stateStore = mock(SearchProjectionStateStore.class);
        ProjectionSink sink = mock(ProjectionSink.class);

        SearchProjectionState state = new SearchProjectionState(
            TENANT_ID, INDEX, "postgres", "gen-1", 100L, "ready", 0, null);
        when(stateStore.find(TENANT_ID, INDEX)).thenReturn(Optional.of(state));
        when(gate.resolve()).thenReturn(Optional.of(sink));
        when(markStore.fetchBatch(TENANT_ID, INDEX, SearchProjectionWorker.CATCHUP_BATCH_SIZE))
            .thenReturn(List.of(
                new MarkRow(101L, MarkKind.KEY, "poison-1"),
                new MarkRow(102L, MarkKind.KEY, "poison-2"),
                new MarkRow(103L, MarkKind.KEY, "poison-3"),
                new MarkRow(104L, MarkKind.KEY, "key-4")));

        SearchProjectionWorker worker = new SearchProjectionWorker(gate, markStore, stateStore);
        worker.metrics = metrics();
        worker.rowBatchReader = everyKeyPresent();
        refusePoison(sink);

        CatchUpResult result = worker.runCatchUp(TENANT_ID, INDEX, decl());

        assertThat(result).isEqualTo(CatchUpResult.FAILED);
        verify(sink, times(1 + 3)).upsert(anyLong(), anyString(), anyString(), anyList()); // the batch, then three marks
        verify(sink, never()).upsert(TENANT_ID, INDEX, "gen-1", List.of(doc("key-4")));
        verify(markStore, never()).deleteByExactIds(anyLong(), anyString(), anyList());
        verify(stateStore, never()).advancePosition(anyLong(), anyString(), anyLong());
        verify(stateStore).markDegraded(eq(TENANT_ID), eq(INDEX), contains("poison document"));
    }

    /** #937: the limit counts failures IN A ROW — a mark that applies resets it, so two refused
     *  documents separated by a good one never add up to a stop. */
    @Test
    void aSuccessfulMarkResetsTheConsecutiveFailureCount() {
        ProjectionSinkGate gate = mock(ProjectionSinkGate.class);
        SearchProjectionMarkStore markStore = mock(SearchProjectionMarkStore.class);
        SearchProjectionStateStore stateStore = mock(SearchProjectionStateStore.class);
        ProjectionSink sink = mock(ProjectionSink.class);

        SearchProjectionState state = new SearchProjectionState(
            TENANT_ID, INDEX, "postgres", "gen-1", 100L, "ready", 0, null);
        when(stateStore.find(TENANT_ID, INDEX)).thenReturn(Optional.of(state));
        when(gate.resolve()).thenReturn(Optional.of(sink));
        when(markStore.fetchBatch(TENANT_ID, INDEX, SearchProjectionWorker.CATCHUP_BATCH_SIZE))
            .thenReturn(List.of(
                new MarkRow(101L, MarkKind.KEY, "poison-1"),
                new MarkRow(102L, MarkKind.KEY, "poison-2"),
                new MarkRow(103L, MarkKind.KEY, "key-3"),
                new MarkRow(104L, MarkKind.KEY, "poison-4"),
                new MarkRow(105L, MarkKind.KEY, "poison-5"),
                new MarkRow(106L, MarkKind.KEY, "key-6")));
        when(markStore.deleteByExactIds(eq(TENANT_ID), eq(INDEX), anyList())).thenReturn(106L);

        SearchProjectionWorker worker = new SearchProjectionWorker(gate, markStore, stateStore);
        worker.metrics = metrics();
        worker.rowBatchReader = everyKeyPresent();
        refusePoison(sink);

        CatchUpResult result = worker.runCatchUp(TENANT_ID, INDEX, decl());

        assertThat(result).isEqualTo(CatchUpResult.FAILED);
        verify(markStore).deleteByExactIds(TENANT_ID, INDEX, List.of(103L, 106L));
        verify(stateStore).markDegraded(eq(TENANT_ID), eq(INDEX), contains("poison document"));
    }

    /** #937: the limit is configurable, and a limit of 1 is the old behaviour — stop at the first failure. */
    @Test
    void aLimitOfOneStopsAtTheFirstFailureAsCatchUpAlwaysDid() {
        ProjectionSinkGate gate = mock(ProjectionSinkGate.class);
        SearchProjectionMarkStore markStore = mock(SearchProjectionMarkStore.class);
        SearchProjectionStateStore stateStore = mock(SearchProjectionStateStore.class);
        ProjectionSink sink = mock(ProjectionSink.class);

        SearchProjectionState state = new SearchProjectionState(
            TENANT_ID, INDEX, "postgres", "gen-1", 100L, "ready", 0, null);
        when(stateStore.find(TENANT_ID, INDEX)).thenReturn(Optional.of(state));
        when(gate.resolve()).thenReturn(Optional.of(sink));
        when(markStore.fetchBatch(TENANT_ID, INDEX, SearchProjectionWorker.CATCHUP_BATCH_SIZE))
            .thenReturn(List.of(
                new MarkRow(101L, MarkKind.KEY, "key-1"),
                new MarkRow(102L, MarkKind.KEY, "poison"),
                new MarkRow(103L, MarkKind.KEY, "key-3")));
        when(markStore.deleteByExactIds(eq(TENANT_ID), eq(INDEX), anyList())).thenReturn(101L);

        SearchProjectionWorker worker = new SearchProjectionWorker(gate, markStore, stateStore);
        worker.metrics = metrics();
        worker.catchupConsecutiveFailureLimit = 1;
        worker.rowBatchReader = everyKeyPresent();
        refusePoison(sink);

        worker.runCatchUp(TENANT_ID, INDEX, decl());

        verify(sink, never()).upsert(TENANT_ID, INDEX, "gen-1", List.of(doc("key-3")));
        verify(markStore).deleteByExactIds(TENANT_ID, INDEX, List.of(101L));
    }

    /** The provider refuses any upsert carrying a document whose id starts with "poison", the batched call included. */
    private static void refusePoison(ProjectionSink sink) {
        doAnswer(inv -> {
            List<ProjectionDocument> docs = inv.getArgument(3);
            if (docs.stream().anyMatch(d -> d.id().startsWith("poison"))) throw new RuntimeException("poison document");
            return null;
        }).when(sink).upsert(anyLong(), anyString(), anyString(), anyList());
    }

    /** #893: the upsert call that succeeded stays applied when the delete call after it throws; the
     *  fallback covers only the marks not applied yet, and is never handed an upsert again. */
    @Test
    void aFailedDeleteCallKeepsTheUpsertsAndFallsBackForTheDeletesOnly() {
        ProjectionSinkGate gate = mock(ProjectionSinkGate.class);
        SearchProjectionMarkStore markStore = mock(SearchProjectionMarkStore.class);
        SearchProjectionStateStore stateStore = mock(SearchProjectionStateStore.class);
        ProjectionSink sink = mock(ProjectionSink.class);

        SearchProjectionState state = new SearchProjectionState(
            TENANT_ID, INDEX, "postgres", "gen-1", 100L, "ready", 0, null);
        when(stateStore.find(TENANT_ID, INDEX)).thenReturn(Optional.of(state));
        when(gate.resolve()).thenReturn(Optional.of(sink));
        when(markStore.fetchBatch(TENANT_ID, INDEX, SearchProjectionWorker.CATCHUP_BATCH_SIZE))
            .thenReturn(List.of(
                new MarkRow(101L, MarkKind.KEY, "gone-1"),
                new MarkRow(102L, MarkKind.KEY, "key-1"),
                new MarkRow(103L, MarkKind.KEY, "gone-2")));
        when(markStore.deleteByExactIds(eq(TENANT_ID), eq(INDEX), anyList())).thenReturn(102L);

        SearchProjectionWorker worker = new SearchProjectionWorker(gate, markStore, stateStore);
        worker.metrics = metrics();
        worker.rowBatchReader = (tenantId, entity, manifest, canonicalKeys) -> Map.of("key-1", doc("key-1"));
        doAnswer(inv -> {
            List<String> ids = inv.getArgument(3);
            if (ids.contains("gone-2")) throw new RuntimeException("delete refused");
            return null;
        }).when(sink).delete(anyLong(), anyString(), anyString(), anyList());

        CatchUpResult result = worker.runCatchUp(TENANT_ID, INDEX, decl());

        assertThat(result).isEqualTo(CatchUpResult.FAILED);
        InOrder order = inOrder(sink);
        order.verify(sink).upsert(TENANT_ID, INDEX, "gen-1", List.of(doc("key-1")));
        order.verify(sink).delete(TENANT_ID, INDEX, "gen-1", List.of("gone-1", "gone-2"));
        order.verify(sink).delete(TENANT_ID, INDEX, "gen-1", List.of("gone-1"));
        order.verify(sink).delete(TENANT_ID, INDEX, "gen-1", List.of("gone-2"));
        verifyNoMoreInteractions(sink);
        assertThat(deletedMarkIds(markStore)).containsExactlyInAnyOrder(101L, 102L);
        verify(stateStore).advancePosition(TENANT_ID, INDEX, 102L);
        verify(stateStore).markDegraded(eq(TENANT_ID), eq(INDEX), contains("delete refused"));
    }

    /** #893: a batched call that throws once but whose per-mark retry applies every mark is a drained
     *  batch, not a failure — nothing is left behind, so nothing is reported degraded. */
    @Test
    void aFailedBatchCallWhosePerMarkRetryAppliesEveryMarkProgresses() {
        ProjectionSinkGate gate = mock(ProjectionSinkGate.class);
        SearchProjectionMarkStore markStore = mock(SearchProjectionMarkStore.class);
        SearchProjectionStateStore stateStore = mock(SearchProjectionStateStore.class);
        ProjectionSink sink = mock(ProjectionSink.class);

        SearchProjectionState state = new SearchProjectionState(
            TENANT_ID, INDEX, "postgres", "gen-1", 100L, "ready", 0, null);
        when(stateStore.find(TENANT_ID, INDEX)).thenReturn(Optional.of(state));
        when(gate.resolve()).thenReturn(Optional.of(sink));
        when(markStore.fetchBatch(TENANT_ID, INDEX, SearchProjectionWorker.CATCHUP_BATCH_SIZE))
            .thenReturn(List.of(new MarkRow(101L, MarkKind.KEY, "key-1"), new MarkRow(102L, MarkKind.KEY, "key-2")));
        when(markStore.deleteByExactIds(eq(TENANT_ID), eq(INDEX), anyList())).thenReturn(102L);

        SearchProjectionWorker worker = new SearchProjectionWorker(gate, markStore, stateStore);
        worker.metrics = metrics();
        worker.rowBatchReader = everyKeyPresent();
        doThrow(new RuntimeException("transient")).doNothing().when(sink)
            .upsert(anyLong(), anyString(), anyString(), anyList());

        CatchUpResult result = worker.runCatchUp(TENANT_ID, INDEX, decl());

        assertThat(result).isEqualTo(CatchUpResult.PROGRESSED);
        verify(sink, times(3)).upsert(anyLong(), anyString(), anyString(), anyList()); // the batch, then key-1, key-2
        assertThat(deletedMarkIds(markStore)).containsExactlyInAnyOrder(101L, 102L);
        verify(stateStore).advancePosition(TENANT_ID, INDEX, 102L);
        verify(stateStore, never()).markDegraded(anyLong(), anyString(), anyString());
    }

    @Test
    void keyMarkWhoseRowIsGoneCallsDeleteNotUpsert() {
        ProjectionSinkGate gate = mock(ProjectionSinkGate.class);
        SearchProjectionMarkStore markStore = mock(SearchProjectionMarkStore.class);
        SearchProjectionStateStore stateStore = mock(SearchProjectionStateStore.class);
        ProjectionSink sink = mock(ProjectionSink.class);

        SearchProjectionState state = new SearchProjectionState(
            TENANT_ID, INDEX, "postgres", "gen-1", 100L, "ready", 0, null);
        when(stateStore.find(TENANT_ID, INDEX)).thenReturn(Optional.of(state));
        when(gate.resolve()).thenReturn(Optional.of(sink));

        MarkRow mark = new MarkRow(101L, MarkKind.KEY, "gone-key");
        when(markStore.fetchBatch(TENANT_ID, INDEX, SearchProjectionWorker.CATCHUP_BATCH_SIZE))
            .thenReturn(List.of(mark));
        when(markStore.deleteByExactIds(TENANT_ID, INDEX, List.of(101L))).thenReturn(101L);

        SearchProjectionWorker worker = new SearchProjectionWorker(gate, markStore, stateStore);
        worker.rowBatchReader = (tenantId, entity, manifest, canonicalKeys) -> Map.of(); // row gone from the view

        SearchIndexManifest manifest = new SearchIndexManifest("idx", ENTITY, "simple",
            List.of(new SearchIndexManifest.Field("name", SearchIndexManifest.Match.EXACT, false)));
        SearchProjectionWorker.SearchIndexDeclaration decl =
            new SearchProjectionWorker.SearchIndexDeclaration("valid", ENTITY, manifest);

        CatchUpResult result = worker.runCatchUp(TENANT_ID, INDEX, decl);

        assertThat(result).isEqualTo(CatchUpResult.PROGRESSED);
        verify(sink, never()).upsert(anyLong(), anyString(), anyString(), anyList());
        verify(sink).delete(TENANT_ID, INDEX, "gen-1", List.of("gone-key")); // the mark's own canonical
                                                                             // key, never a reconstructed key map
        verify(stateStore).advancePosition(TENANT_ID, INDEX, 101L);
    }

    /** I2 fix (#671 final review, design doc §5c step 5): a later successful batch clears a
     *  previously degraded row's status/retry_count/last_error — a pair that keeps succeeding must
     *  not be reported degraded forever off one stale attempt. */
    @Test
    void aFullySuccessfulBatchClearsAPreviouslyDegradedStatus() {
        ProjectionSinkGate gate = mock(ProjectionSinkGate.class);
        SearchProjectionMarkStore markStore = mock(SearchProjectionMarkStore.class);
        SearchProjectionStateStore stateStore = mock(SearchProjectionStateStore.class);
        ProjectionSink sink = mock(ProjectionSink.class);

        SearchProjectionState state = new SearchProjectionState(
            TENANT_ID, INDEX, "postgres", "gen-1", 100L, "degraded", 3, "boom");
        when(stateStore.find(TENANT_ID, INDEX)).thenReturn(Optional.of(state));
        when(gate.resolve()).thenReturn(Optional.of(sink));

        MarkRow mark = new MarkRow(101L, MarkKind.KEY, "key-1");
        when(markStore.fetchBatch(TENANT_ID, INDEX, SearchProjectionWorker.CATCHUP_BATCH_SIZE))
            .thenReturn(List.of(mark));
        when(markStore.deleteByExactIds(TENANT_ID, INDEX, List.of(101L))).thenReturn(101L);

        SearchProjectionWorker worker = new SearchProjectionWorker(gate, markStore, stateStore);
        worker.rowBatchReader = (tenantId, entity, manifest, canonicalKeys) ->
            Map.of("key-1", new ProjectionDocument("key-1", Map.of("name", "key-1"), Map.of()));

        SearchIndexManifest manifest = new SearchIndexManifest("idx", ENTITY, "simple",
            List.of(new SearchIndexManifest.Field("name", SearchIndexManifest.Match.EXACT, false)));
        SearchProjectionWorker.SearchIndexDeclaration decl =
            new SearchProjectionWorker.SearchIndexDeclaration("valid", ENTITY, manifest);

        CatchUpResult result = worker.runCatchUp(TENANT_ID, INDEX, decl);

        assertThat(result).isEqualTo(CatchUpResult.PROGRESSED);
        verify(stateStore).advancePosition(TENANT_ID, INDEX, 101L);
        verify(stateStore).markReadyAfterCatchup(TENANT_ID, INDEX);
        verify(stateStore, never()).markDegraded(anyLong(), anyString(), anyString());
    }

    /** The healthy-row twin: nothing calls {@code markReadyAfterCatchup} when the row was never
     *  degraded to begin with — it is conditional on status, not fired unconditionally on success. */
    @Test
    void aSuccessfulBatchOnAnAlreadyReadyRowNeverCallsMarkReadyAfterCatchup() {
        ProjectionSinkGate gate = mock(ProjectionSinkGate.class);
        SearchProjectionMarkStore markStore = mock(SearchProjectionMarkStore.class);
        SearchProjectionStateStore stateStore = mock(SearchProjectionStateStore.class);
        ProjectionSink sink = mock(ProjectionSink.class);

        SearchProjectionState state = new SearchProjectionState(
            TENANT_ID, INDEX, "postgres", "gen-1", 100L, "ready", 0, null);
        when(stateStore.find(TENANT_ID, INDEX)).thenReturn(Optional.of(state));
        when(gate.resolve()).thenReturn(Optional.of(sink));

        MarkRow mark = new MarkRow(101L, MarkKind.KEY, "key-1");
        when(markStore.fetchBatch(TENANT_ID, INDEX, SearchProjectionWorker.CATCHUP_BATCH_SIZE))
            .thenReturn(List.of(mark));
        when(markStore.deleteByExactIds(TENANT_ID, INDEX, List.of(101L))).thenReturn(101L);

        SearchProjectionWorker worker = new SearchProjectionWorker(gate, markStore, stateStore);
        worker.rowBatchReader = (tenantId, entity, manifest, canonicalKeys) ->
            Map.of("key-1", new ProjectionDocument("key-1", Map.of("name", "key-1"), Map.of()));

        SearchIndexManifest manifest = new SearchIndexManifest("idx", ENTITY, "simple",
            List.of(new SearchIndexManifest.Field("name", SearchIndexManifest.Match.EXACT, false)));
        SearchProjectionWorker.SearchIndexDeclaration decl =
            new SearchProjectionWorker.SearchIndexDeclaration("valid", ENTITY, manifest);

        worker.runCatchUp(TENANT_ID, INDEX, decl);

        verify(stateStore, never()).markReadyAfterCatchup(anyLong(), anyString());
    }

    @Test
    void catchupFailureIncrementsTheCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SearchProjectionMetrics metrics = new SearchProjectionMetrics();
        metrics.registry = registry;

        ProjectionSinkGate gate = mock(ProjectionSinkGate.class);
        SearchProjectionMarkStore markStore = mock(SearchProjectionMarkStore.class);
        SearchProjectionStateStore stateStore = mock(SearchProjectionStateStore.class);
        ProjectionSink sink = mock(ProjectionSink.class);

        SearchProjectionState state = new SearchProjectionState(
            TENANT_ID, INDEX, "postgres", "gen-1", 100L, "ready", 0, null);
        when(stateStore.find(TENANT_ID, INDEX)).thenReturn(Optional.of(state));
        when(gate.resolve()).thenReturn(Optional.of(sink));

        MarkRow mark1 = new MarkRow(101L, MarkKind.KEY, "key-1");
        MarkRow mark2 = new MarkRow(102L, MarkKind.KEY, "key-2");
        when(markStore.fetchBatch(TENANT_ID, INDEX, SearchProjectionWorker.CATCHUP_BATCH_SIZE))
            .thenReturn(List.of(mark1, mark2));

        SearchProjectionWorker worker = new SearchProjectionWorker(gate, markStore, stateStore);
        worker.metrics = metrics; // package-private field, same test-seam idiom as rowBatchReader
        worker.rowBatchReader = everyKeyPresent();
        // Every call throws, the batched one and its per-mark fallback alike.
        doThrow(new RuntimeException("boom")).when(sink)
            .upsert(anyLong(), anyString(), anyString(), anyList());

        worker.runCatchUp(TENANT_ID, INDEX, decl());

        assertThat(registry.counter("lumnik_search_catchup_failures_total").count()).isEqualTo(1.0);
    }

    private static SearchProjectionWorker.SearchIndexDeclaration decl() {
        SearchIndexManifest manifest = new SearchIndexManifest("idx", ENTITY, "simple",
            List.of(new SearchIndexManifest.Field("name", SearchIndexManifest.Match.EXACT, false)));
        return new SearchProjectionWorker.SearchIndexDeclaration("valid", ENTITY, manifest);
    }

    private static ProjectionDocument doc(String key) {
        return new ProjectionDocument(key, Map.of("name", key), Map.of());
    }

    /** Every requested key still has its row in the view. */
    private static SearchProjectionWorker.RowBatchReader everyKeyPresent() {
        return (tenantId, entity, manifest, canonicalKeys) -> {
            Map<String, ProjectionDocument> byKey = new LinkedHashMap<>();
            for (String key : canonicalKeys) byKey.put(key, doc(key));
            return byKey;
        };
    }

    private static SearchProjectionMetrics metrics() {
        SearchProjectionMetrics metrics = new SearchProjectionMetrics();
        metrics.registry = new SimpleMeterRegistry();
        return metrics;
    }

    /** The ids handed to the one {@code deleteByExactIds} call, in whatever order the worker chose. */
    @SuppressWarnings("unchecked")
    private static List<Long> deletedMarkIds(SearchProjectionMarkStore markStore) {
        ArgumentCaptor<List<Long>> ids = ArgumentCaptor.forClass(List.class);
        verify(markStore).deleteByExactIds(eq(TENANT_ID), eq(INDEX), ids.capture());
        return ids.getValue();
    }
}
