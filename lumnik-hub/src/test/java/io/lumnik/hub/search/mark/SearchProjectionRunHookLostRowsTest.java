package io.lumnik.hub.search.mark;

import io.lumnik.extraction.spi.search.ProjectionSink;
import io.lumnik.hub.search.ProjectionSinkGate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * #1195: rows removed from a source table cannot be found to be KEY-marked, so every valid index
 * over an entity observing the table is marked ALL — and only those.
 */
class SearchProjectionRunHookLostRowsTest {

  @Test
  void every_index_over_an_entity_observing_the_table_rebuilds() throws Exception {
    SearchProjectionRunHook hook = spy(new SearchProjectionRunHook());
    hook.sinkGate = mock(ProjectionSinkGate.class);
    hook.markStore = mock(SearchProjectionMarkStore.class);
    when(hook.sinkGate.resolve()).thenReturn(Optional.of(mock(ProjectionSink.class)));
    doReturn(Map.of(
        "t_ref", List.of(new SearchProjectionRunHook.SourceTarget("Customer", List.of("code"), List.of("cust-a", "cust-b"), List.of("\"code\""))),
        "t_other", List.of(new SearchProjectionRunHook.SourceTarget("Order", List.of("no"), List.of("orders"), List.of("\"no\"")))))
        .when(hook).targetsByTable(1L);

    hook.tableLostRows(1L, "t_ref");

    verify(hook.markStore).markAll(1L, "cust-a", "Customer", "source-rows-removed");
    verify(hook.markStore).markAll(1L, "cust-b", "Customer", "source-rows-removed");
    verifyNoMoreInteractions(hook.markStore);
  }

  @Test
  void without_a_search_provider_nothing_is_marked() throws Exception {
    SearchProjectionRunHook hook = new SearchProjectionRunHook();
    hook.sinkGate = mock(ProjectionSinkGate.class);
    hook.markStore = mock(SearchProjectionMarkStore.class);
    when(hook.sinkGate.resolve()).thenReturn(Optional.empty());

    hook.tableLostRows(1L, "t_ref");

    verify(hook.markStore, never()).markAll(anyLong(), anyString(), anyString(), anyString());
  }
}
