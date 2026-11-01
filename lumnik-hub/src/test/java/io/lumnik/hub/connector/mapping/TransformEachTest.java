package io.lumnik.hub.connector.mapping;

import io.lumnik.hub.connector.mapping.spec.MappingLoader;
import io.lumnik.hub.connector.mapping.spec.MappingSpec;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** #1617: each row stays beside its payload — what it became, or null where a hook dropped it; never several. */
@QuarkusTest
class TransformEachTest {

  @Inject MappingEngine engine;

  private static MappingSpec spec(String hooks) {
    return MappingLoader.load("id: each\nversion: 1\ntarget: x\n" + hooks);
  }

  private static List<Map<String, Object>> pods() {
    List<Map<String, Object>> rows = new ArrayList<>();
    rows.add(new HashMap<>(Map.of("pod", "a", "phase", "Running")));
    rows.add(new HashMap<>(Map.of("pod", "b", "phase", "Pending")));
    rows.add(new HashMap<>(Map.of("pod", "c", "phase", "Running")));
    return rows;
  }

  @Test
  void aDroppedRowLeavesANullInItsPlace_soTheOthersKeepTheirPayload() {
    MappingState state = new MappingState();

    List<Map<String, Object>> out = engine.transformEach(
        spec("dataset:\n  - { filter-where: { column: phase, equals: Running } }\n"), pods(), state);

    assertThat(out).hasSize(3);
    assertThat(out.get(0)).containsEntry("pod", "a");
    assertThat(out.get(1)).isNull();
    assertThat(out.get(2)).containsEntry("pod", "c");
    assertThat(state.dropped()).singleElement().satisfies(d -> assertThat(d.row()).containsEntry("pod", "b"));
  }

  @Test
  void aRowSplitInSeveralIsRefused() {
    List<Map<String, Object>> rows = List.of(new HashMap<>(Map.of("pod", "a", "ports", "80,443")));

    assertThatThrownBy(() -> engine.transformEach(spec("row:\n  - { split-row: { by: ports } }\n"), rows,
        new MappingState()))
        .isInstanceOf(MappingValidationException.class)
        .hasMessageContaining("2 rows of one");
  }

  @Test
  void anAggregatorIsRefusedBeforeAnyRow() {
    assertThatThrownBy(() -> engine.transformEach(
        spec("row:\n  - { aggregator: { emit-on-change: pod } }\n"), List.of(), new MappingState()))
        .isInstanceOf(MappingValidationException.class)
        .hasMessageContaining("aggregator");
  }
}
