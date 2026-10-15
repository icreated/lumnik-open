package io.lumnik.hub.search.projection;

import io.lumnik.extraction.spi.search.ProjectionSink;
import io.lumnik.hub.entity.resolution.ResolutionCoverageStore;
import io.lumnik.hub.search.mark.SearchProjectionMarkStore;
import io.lumnik.hub.search.mark.SearchProjectionRunHook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SearchProjectionAsOfAdvancerTest {

    static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");
    static final Instant W1 = Instant.parse("2026-09-30T11:00:00Z");
    static final Instant W2 = Instant.parse("2026-09-30T11:30:00Z");

    SearchProjectionRunHook hook = mock(SearchProjectionRunHook.class);
    SearchProjectionMarkStore marks = mock(SearchProjectionMarkStore.class);
    ProjectionSink sink = mock(ProjectionSink.class);
    SearchProjectionAsOfAdvancer advancer = new SearchProjectionAsOfAdvancer();

    @BeforeEach
    void wire() {
        advancer.runHook = hook;
        advancer.markStore = marks;
        advancer.entityOf = (t, i) -> Optional.of("customer");
        advancer.watermarkOf = (t, table) -> "connector.t_a".equals(table) ? Optional.of(W1) : Optional.of(W2);
        when(hook.safeBoundAdmin()).thenReturn(NOW);
        when(hook.matchSourceTables(anyString())).thenReturn(List.of("connector.t_a", "connector.t_b"));
        when(marks.hasPendingMarkAdmin(anyLong(), anyString())).thenReturn(false);
    }

    @Test
    void boundIsTheMinimumOfSafeBoundAndEverySourceWatermark() {
        assertThat(advancer.advance(1L, "idx", sink, "g7")).contains(W1);
        verify(sink).advanceAsOf(1L, "idx", "g7", W1);
    }

    @Test
    void aLookupTableWithNoWatermarkYetStopsTheBound() {   // #847: the lookup of a join is a table the bound waits for
        when(hook.matchSourceTables(anyString())).thenReturn(List.of("connector.t_a", "connector.t_lookup"));
        advancer.watermarkOf = (t, table) -> "connector.t_a".equals(table) ? Optional.of(W1) : Optional.empty();

        assertThat(advancer.advance(1L, "idx", sink, "g7")).isEmpty();

        verifyNoInteractions(sink);
    }

    @Test
    void aLookupWatermarkBehindTheSourcesCapsTheBound() {
        when(hook.matchSourceTables(anyString())).thenReturn(List.of("connector.t_a", "connector.t_lookup"));
        advancer.watermarkOf = (t, table) -> "connector.t_a".equals(table) ? Optional.of(W2) : Optional.of(W1);

        assertThat(advancer.advance(1L, "idx", sink, "g7")).contains(W1);
    }

    @Test
    void safeBoundCapsAWatermarkThatIsAheadOfIt() {
        advancer.watermarkOf = (t, table) -> Optional.of(NOW.plusSeconds(60));
        assertThat(advancer.advance(1L, "idx", sink, "g7")).contains(NOW);
    }

    @Test
    void aMissingWatermarkRowStopsThePathWithNoCall() {
        advancer.watermarkOf = (t, table) -> "connector.t_a".equals(table) ? Optional.of(W1) : Optional.empty();
        assertThat(advancer.advance(1L, "idx", sink, "g7")).isEmpty();
        verify(sink, never()).advanceAsOf(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void aPendingMarkStopsThePathWithNoCall() {
        when(marks.hasPendingMarkAdmin(1L, "idx")).thenReturn(true);
        assertThat(advancer.advance(1L, "idx", sink, "g7")).isEmpty();
        verify(sink, never()).advanceAsOf(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void aResolutionUnknownOrSourcelessEntityStopsThePathBeforeAnyQuery() {
        when(hook.matchSourceTables(anyString())).thenReturn(List.of());
        assertThat(advancer.advance(1L, "idx", sink, "g7")).isEmpty();
        verifyNoInteractions(sink);
        verify(marks, never()).hasPendingMarkAdmin(anyLong(), anyString());
    }

    @Test
    void anUnknownDeclarationStopsThePath() {
        advancer.entityOf = (t, i) -> Optional.empty();
        assertThat(advancer.advance(1L, "idx", sink, "g7")).isEmpty();
        verifyNoInteractions(sink);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theOrderIsBoundThenWatermarksThenQueueCheckThenAdvance() {
        BiFunction<Long, String, Optional<Instant>> watermarks = mock(BiFunction.class);
        when(watermarks.apply(anyLong(), anyString())).thenReturn(Optional.of(W1));
        advancer.watermarkOf = watermarks;

        advancer.advance(1L, "idx", sink, "g7");

        // The watermark MUST be read before the queue check: a producer commits marks and then its
        // watermark, so a watermark read after an "empty" verdict could cover marks that check missed.
        InOrder order = inOrder(hook, watermarks, marks, sink);
        order.verify(hook).safeBoundAdmin();
        order.verify(watermarks, atLeastOnce()).apply(anyLong(), anyString());
        order.verify(marks).hasPendingMarkAdmin(1L, "idx");
        order.verify(sink).advanceAsOf(1L, "idx", "g7", W1);
    }

    @Test
    void aResolutionEntityStopsOnAnUnknownBoundWithoutTouchingTheQueue() {
        when(hook.isResolutionEntity("customer")).thenReturn(true);
        when(hook.matchSourceTables(anyString())).thenReturn(List.of());   // no scanned source: not a match: entity
        advancer.resolutionBoundOf = (t, e) -> new ResolutionCoverageStore.UncoveredBound(true, null);

        assertThat(advancer.advance(1L, "idx", sink, "g7")).isEmpty();

        verify(marks, never()).hasPendingMarkAdmin(anyLong(), anyString());
        verify(sink, never()).advanceAsOf(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void aResolutionBoundIsTheEarlierOfSafeBoundAndTheEarliestUncoveredFence() {
        advancer.watermarkOf = (t, table) -> { throw new AssertionError("a resolution: entity reads no source watermark"); };
        when(hook.isResolutionEntity("customer")).thenReturn(true);
        when(hook.matchSourceTables(anyString())).thenReturn(List.of());   // no scanned source: not a match: entity
        advancer.resolutionBoundOf = (t, e) -> new ResolutionCoverageStore.UncoveredBound(false, W1);
        assertThat(advancer.advance(1L, "idx", sink, "g7")).contains(W1);           // fence before safeBound

        advancer.resolutionBoundOf = (t, e) -> new ResolutionCoverageStore.UncoveredBound(false, NOW.plusSeconds(60));
        assertThat(advancer.advance(1L, "idx", sink, "g7")).contains(NOW);          // safeBound before fence

        advancer.resolutionBoundOf = (t, e) -> ResolutionCoverageStore.UncoveredBound.NONE;
        assertThat(advancer.advance(1L, "idx", sink, "g7")).contains(NOW);          // nothing uncovered
    }

    @Test
    void theCoverageKeyIsTheLowerCasedEntityName() {
        when(hook.isResolutionEntity("Customer")).thenReturn(true);
        when(hook.matchSourceTables(anyString())).thenReturn(List.of());   // no scanned source: not a match: entity
        advancer.entityOf = (t, i) -> Optional.of("Customer");
        List<String> keys = new ArrayList<>();
        advancer.resolutionBoundOf = (t, e) -> { keys.add(e); return ResolutionCoverageStore.UncoveredBound.NONE; };

        advancer.advance(1L, "idx", sink, "g7");

        assertThat(keys).containsExactly("customer");
    }

    @Test
    void aResolutionEntityOrderIsBoundThenCoverageThenQueueThenAdvance() {
        when(hook.isResolutionEntity("customer")).thenReturn(true);
        when(hook.matchSourceTables(anyString())).thenReturn(List.of());   // no scanned source: not a match: entity
        List<String> calls = new ArrayList<>();
        when(hook.safeBoundAdmin()).thenAnswer(i -> { calls.add("safeBound"); return NOW; });
        advancer.resolutionBoundOf = (t, e) -> { calls.add("coverage"); return ResolutionCoverageStore.UncoveredBound.NONE; };
        when(marks.hasPendingMarkAdmin(anyLong(), anyString())).thenAnswer(i -> { calls.add("queue"); return false; });
        doAnswer(i -> { calls.add("advance"); return null; })
            .when(sink).advanceAsOf(anyLong(), anyString(), anyString(), any());

        advancer.advance(1L, "idx", sink, "g7");

        assertThat(calls).containsExactly("safeBound", "coverage", "queue", "advance");
    }

    @Test
    void aResolutionEntityWithPendingMarksStopsWithNoCall() {
        when(hook.isResolutionEntity("customer")).thenReturn(true);
        when(hook.matchSourceTables(anyString())).thenReturn(List.of());   // no scanned source: not a match: entity
        advancer.resolutionBoundOf = (t, e) -> ResolutionCoverageStore.UncoveredBound.NONE;
        when(marks.hasPendingMarkAdmin(1L, "idx")).thenReturn(true);

        assertThat(advancer.advance(1L, "idx", sink, "g7")).isEmpty();
        verify(sink, never()).advanceAsOf(anyLong(), anyString(), anyString(), any());
    }

    // #850: a resolution entity's bound also waits for every lookup watermark

    private void asResolutionEntityWithLookups(String... lookupTables) {
        when(hook.matchSourceTables(anyString())).thenReturn(List.of());
        when(hook.isResolutionEntity(anyString())).thenReturn(true);
        when(hook.resolutionLookupTables(anyString())).thenReturn(List.of(lookupTables));
        advancer.resolutionBoundOf = (t, e) -> ResolutionCoverageStore.UncoveredBound.NONE;
    }

    @Test
    void aResolutionEntityWithoutLookupsKeepsTheCoverageOnlyBound() {
        asResolutionEntityWithLookups();
        assertThat(advancer.advance(1L, "idx", sink, "g7")).contains(NOW);
    }

    @Test
    void aLookupWatermarkBehindTheSafeBoundCapsAResolutionBound() {
        asResolutionEntityWithLookups("connector.t_lookup");
        advancer.watermarkOf = (t, table) -> Optional.of(W1);
        assertThat(advancer.advance(1L, "idx", sink, "g7")).contains(W1);
        verify(sink).advanceAsOf(1L, "idx", "g7", W1);
    }

    @Test
    void theEarliestOfFenceAndLookupWatermarksWins() {
        asResolutionEntityWithLookups("connector.t_l1", "connector.t_l2");
        advancer.resolutionBoundOf = (t, e) -> new ResolutionCoverageStore.UncoveredBound(false, W2);
        advancer.watermarkOf = (t, table) -> "connector.t_l1".equals(table) ? Optional.of(W1) : Optional.of(NOW);
        assertThat(advancer.advance(1L, "idx", sink, "g7")).contains(W1);
    }

    @Test
    void aResolutionLookupWithNoWatermarkYetStopsTheAdvance() {
        asResolutionEntityWithLookups("connector.t_lookup");
        advancer.watermarkOf = (t, table) -> Optional.empty();
        assertThat(advancer.advance(1L, "idx", sink, "g7")).isEmpty();
        verifyNoInteractions(sink);
    }

    @Test
    void anUnknownFenceStillStopsAResolutionEntityWhateverTheLookupsSay() {
        asResolutionEntityWithLookups("connector.t_lookup");
        advancer.resolutionBoundOf = (t, e) -> new ResolutionCoverageStore.UncoveredBound(true, null);
        advancer.watermarkOf = (t, table) -> Optional.of(NOW);
        assertThat(advancer.advance(1L, "idx", sink, "g7")).isEmpty();
        verifyNoInteractions(sink);
    }
}
