package io.lumnik.hub.connector.mapping;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MappingStateTest {

  @Test
  void state_carries_aggregator_buffer_across_chunks() {
    MappingState state = new MappingState();
    var agg = state.aggregator("g1");
    agg.currentKey("incident-1");
    agg.addToBuffer(Map.of("assignee", "alice"));
    agg.addToBuffer(Map.of("assignee", "bob"));

    var same = state.aggregator("g1");
    assertThat(same.currentKey()).isEqualTo("incident-1");
    assertThat(same.buffer()).hasSize(2);
  }

  @Test
  void counters_are_atomic() {
    MappingState state = new MappingState();
    state.counters().cellErrors().incrementAndGet();
    state.counters().cellErrors().incrementAndGet();
    assertThat(state.counters().cellErrors().get()).isEqualTo(2);
  }

  @Test
  void clearing_buffer_keeps_aggregator_state() {
    MappingState state = new MappingState();
    var agg = state.aggregator("k");
    agg.currentKey("a");
    agg.addToBuffer(Map.of("v","1"));
    agg.clearBuffer();
    assertThat(agg.buffer()).isEmpty();
    assertThat(agg.currentKey()).isEqualTo("a");
  }
}
