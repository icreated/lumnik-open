package io.lumnik.hub.connector.mapping;

import io.lumnik.hub.connector.runtime.OwnedRunScope;
import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.SkipReason;
import io.lumnik.hub.connector.api.types.SyncMode;
import io.lumnik.hub.connector.api.types.WriteOutcome;
import io.lumnik.hub.connector.mapping.spec.MappingLoader;
import io.lumnik.hub.connector.mapping.spec.MappingSpec;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spec test 1 (docs/superpowers/specs/2026-08-20-mapping-drop-accounting-design.md §"Tests"):
 * hooks that drop rows must declare it in {@link MappingState#dropped()} instead of the dead
 * {@code Counters.rowsDlq} counter this replaces.
 */
@QuarkusTest
class MappingDropAccountingTest {

  @Inject MappingEngine engine;

  @Test
  void validate_row_and_filter_where_each_declare_their_drops() {
    MappingSpec spec = MappingLoader.load("""
        id: drop-accounting
        version: 1
        target: x
        dataset:
          - { filter-where: { column: status, equals: ACTIVE } }
        row:
          - { validate-row: { required: [name] } }
        """);

    List<Map<String, Object>> input = List.of(
        new HashMap<>(Map.of("name", "Alice", "status", "ACTIVE")),
        new HashMap<>(Map.of("name", "", "status", "ACTIVE")),
        new HashMap<>(Map.of("name", "Carol", "status", "INACTIVE")),
        new HashMap<>(Map.of("name", "Dave", "status", "ACTIVE")));

    MappingState state = new MappingState();
    var output = engine.transform(spec, input, state, false);

    assertThat(output).hasSize(2);
    assertThat(output.stream().map(r -> r.get("name"))).containsExactlyInAnyOrder("Alice", "Dave");

    assertThat(state.dropped()).hasSize(2);
    assertThat(state.dropped()).anySatisfy(d -> {
      assertThat(d.reason()).isInstanceOf(SkipReason.ValidationFailed.class);
      assertThat(d.row()).containsEntry("name", "");
    });
    assertThat(state.dropped()).anySatisfy(d -> {
      assertThat(d.reason()).isInstanceOf(SkipReason.PolicyExcluded.class);
      assertThat(d.row()).containsEntry("name", "Carol");
      assertThat(((SkipReason.PolicyExcluded) d.reason()).policyName()).isEqualTo("filter-where");
    });
  }

  @Test
  void row_chain_that_emits_nothing_without_declaring_a_drop_trips_the_defensive_net() {
    MappingSpec spec = MappingLoader.load("""
        id: drop-accounting-net
        version: 1
        target: x
        row:
          - { test-silent-drop: {} }
        """);

    List<Map<String, Object>> input = List.of(new HashMap<>(Map.of("name", "Eve")));

    MappingState state = new MappingState();
    var output = engine.transform(spec, input, state, false);

    assertThat(output).isEmpty();
    assertThat(state.dropped()).hasSize(1);
    assertThat(state.dropped().get(0).reason()).isInstanceOf(SkipReason.MappingFailed.class);
    assertThat(state.dropped().get(0).row()).containsEntry("name", "Eve");
  }

  /**
   * Per-culprit regression: the OLD net stood down for the WHOLE chain whenever the spec
   * declared an {@code aggregator} hook ANYWHERE, even when the hook that actually emptied the
   * chain was a different, non-aggregator hook. Here {@code test-silent-drop} runs FIRST and
   * empties the chain by itself — the loop breaks before {@code aggregator} ever runs — so the
   * culprit is {@code test-silent-drop}, not the aggregator. The net must trip regardless of the
   * aggregator's mere presence later in the spec.
   */
  @Test
  void non_aggregator_hook_emptying_the_chain_trips_the_net_even_when_an_aggregator_exists_later() {
    MappingSpec spec = MappingLoader.load("""
        id: drop-accounting-net-culprit
        version: 1
        target: x
        row:
          - { test-silent-drop: {} }
          - { aggregator: { emit-on-change: k } }
        """);

    List<Map<String, Object>> input = List.of(new HashMap<>(Map.of("name", "Eve", "k", "1")));

    MappingState state = new MappingState();
    var output = engine.transform(spec, input, state, false);

    assertThat(output).isEmpty();
    assertThat(state.dropped()).hasSize(1);
    assertThat(state.dropped().get(0).reason()).isInstanceOf(SkipReason.MappingFailed.class);
    assertThat(((SkipReason.MappingFailed) state.dropped().get(0).reason()).field())
        .isEqualTo("test-silent-drop");
    assertThat(state.dropped().get(0).row()).containsEntry("name", "Eve");
  }

  /**
   * The legitimate case, still not tripping: when the aggregator ITSELF is the hook that empties
   * the chain (buffering the first row of a new group), that is not a drop — no other hook ran
   * after it, so there is nothing else to blame.
   */
  @Test
  void aggregator_itself_buffering_to_empty_does_not_trip_the_net() {
    MappingSpec spec = MappingLoader.load("""
        id: drop-accounting-net-legit-aggregator
        version: 1
        target: x
        row:
          - { aggregator: { emit-on-change: k } }
        """);

    List<Map<String, Object>> input = List.of(new HashMap<>(Map.of("name", "Frank", "k", "1")));

    MappingState state = new MappingState();
    var output = engine.transform(spec, input, state, false);

    assertThat(output).isEmpty();
    assertThat(state.dropped()).isEmpty();
  }

  @Test
  void split_row_fan_out_is_not_a_drop() {
    MappingSpec spec = MappingLoader.load("""
        id: drop-accounting-fanout
        version: 1
        target: x
        row:
          - { split-row: { explode: tags } }
        """);

    List<Map<String, Object>> input = List.of(
        new HashMap<>(Map.of("name", "A")),
        new HashMap<>(Map.of("name", "B", "tags", List.of("x", "y"))),
        new HashMap<>(Map.of("name", "C")));

    MappingState state = new MappingState();
    var output = engine.transform(spec, input, state, false);

    assertThat(output).hasSize(4);
    assertThat(state.dropped()).isEmpty();
  }

  /**
   * Reviewer-found composition (Important, found by RUNNING it, not by inspection): split-row
   * fans 1 source row into N fragments; validate-row placed AFTER it drops each failing fragment
   * separately, so drops.size() can exceed the source-row count. Left unguarded,
   * {@code MappingSpecItemWriter.write}'s {@code items.size() - drops.size()} arithmetic goes
   * negative and {@code WriteOutcome.PartialWrite} reports a negative {@code written} count — the
   * honest-counting fix turned dishonest. The validator now refuses this manifest shape at
   * validate-time (see {@code CsvFileConnectorValidatorTest}); this test drives the engine
   * directly, bypassing the validator, to prove the belt-and-braces guard in
   * {@code MappingEngine.transformAndWrite} also refuses rather than silently corrupting the
   * conservation invariant.
   */
  @Test
  void validate_row_after_split_row_would_go_negative_so_the_engine_refuses() {
    MappingSpec spec = MappingLoader.load("""
        id: drop-accounting-split-then-validate
        version: 1
        target: x
        row:
          - { split-row: { explode: tags } }
          - { validate-row: { required: [name] } }
        """);

    // 1 source row, no "name" — split-row fans it into 2 fragments (tags: x, tags: y), and
    // validate-row drops BOTH individually: 2 drops recorded for 1 source row.
    List<Map<String, Object>> input = List.of(new HashMap<>(Map.of("tags", List.of("x", "y"))));

    assertThatThrownBy(() ->
        engine.transformAndWrite(spec, input, 1L, UUID.randomUUID(), false, null, List.of()))
        .isInstanceOf(MappingValidationException.class)
        .hasMessageContaining("drops")
        .hasMessageContaining("source rows");
  }

  /**
   * Carried from the Task 2 re-review: the legal boundary. {@code drops.size() == input.size()}
   * (every source row dropped, WITHOUT split-row fan-out) is honest, not a corruption — the
   * engine guard ({@code MappingEngine.transformAndWrite}) only refuses when drops OUTNUMBER
   * source rows ({@code >}, never {@code >=}). Must not throw, and must report the drop count
   * honestly at both the engine and the writer layer.
   */
  @Test
  void all_rows_dropped_without_split_row_is_the_legal_boundary_not_a_refusal() {
    MappingSpec spec = MappingLoader.load("""
        id: drop-accounting-all-dropped
        version: 1
        target: x
        row:
          - { validate-row: { required: [name] } }
        """);

    List<Map<String, Object>> input = List.of(
        new HashMap<>(Map.of("name", "")),
        new HashMap<>(Map.of("name", "")),
        new HashMap<>(Map.of("name", "")));

    // Engine layer: transformAndWrite must NOT throw, and must return all 3 as drops.
    var mappingOutcome = engine.transformAndWrite(spec, input, 1L, UUID.randomUUID(), false, null, List.of());
    var drops = mappingOutcome.dropped();
    assertThat(drops).hasSize(3);
    assertThat(drops).allSatisfy(d ->
        assertThat(d.reason()).isInstanceOf(SkipReason.ValidationFailed.class));

    // Writer layer: the same input through MappingSpecItemWriter reports PartialWrite(0, ...) —
    // never a negative or clamped-to-zero lie, the honest zero.
    List<Map<String, Object>> writerInput = List.of(
        new HashMap<>(Map.of("name", "")),
        new HashMap<>(Map.of("name", "")),
        new HashMap<>(Map.of("name", "")));
    ConnectorContext ctx = new ConnectorContext(
        UUID.randomUUID(), null, null, 1L, "test", "test", Map.of(),
        new SyncMode.FullRefresh(),
        null, null, Clock.systemUTC(), null, new OwnedRunScope("test"));
    WriteOutcome outcome = engine.itemWriter(spec).write(writerInput, ctx);

    assertThat(outcome).isInstanceOf(WriteOutcome.PartialWrite.class);
    var partial = (WriteOutcome.PartialWrite) outcome;
    assertThat(partial.written()).isEqualTo(0);
    assertThat(partial.skipped()).hasSize(3);
  }
}
