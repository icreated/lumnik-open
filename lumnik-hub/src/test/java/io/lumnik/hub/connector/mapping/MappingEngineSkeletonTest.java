package io.lumnik.hub.connector.mapping;

import io.lumnik.hub.connector.mapping.spec.MappingLoader;
import io.lumnik.hub.connector.mapping.spec.MappingSpec;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class MappingEngineSkeletonTest {

  @Inject MappingEngine engine;

  @Test
  void empty_mapping_passes_rows_through_unchanged() {
    MappingSpec spec = MappingLoader.load("""
      id: noop
      version: 1
      target: x
      """);
    MappingState state = new MappingState();
    List<Map<String, Object>> input = List.of(
        new HashMap<>(Map.of("a", "1")),
        new HashMap<>(Map.of("a", "2")));
    var output = engine.transform(spec, input, state, false);
    assertThat(output).hasSize(2);
    assertThat(output.get(0)).containsEntry("a", "1");
    assertThat(state.counters().rowsIn().get()).isEqualTo(2);
    assertThat(state.counters().rowsOut().get()).isEqualTo(2);
  }

  @Test
  void unknown_hook_throws_validation_exception() {
    MappingSpec spec = MappingLoader.load("""
      id: t
      version: 1
      target: x
      dataset:
        - { unknown-hook-xxx: true }
      """);
    assertThatThrownBy(() -> engine.transform(spec, List.of(Map.of("a", "1")), new MappingState(), false))
      .isInstanceOf(MappingValidationException.class)
      .hasMessageContaining("unknown-hook-xxx");
  }
}
