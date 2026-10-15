package io.lumnik.hub.entity.resolution;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lot #616 of the #613 entity-resolution arc (design doc §5): the internal orchestrator that turns
 * a declared {@code normalize:} chain into a normalized value, on an isolated cell — never the
 * {@code CellHook.apply} contract's row/{@code MappingState} authority.
 */
@QuarkusTest
class NormalizationOrchestratorTest {

  @Inject NormalizationOrchestrator orchestrator;

  private static final String FIELD = "value";

  @Test
  void applies_a_single_admitted_hook() {
    assertThat(orchestrator.normalize(List.of("lower"), FIELD, "Alice@Example.COM"))
        .isEqualTo("alice@example.com");
  }

  @Test
  void applies_a_chain_of_admitted_hooks_in_declared_order() {
    assertThat(orchestrator.normalize(List.of("remove-diacritics", "lower"), FIELD, "Café"))
        .isEqualTo("cafe");
  }

  @Test
  void order_matters_for_a_chain() {
    // upper then lower ends lowercase either way here — pick hooks where order is observable.
    String upperThenTitle = orchestrator.normalize(List.of("upper", "titlecase"), FIELD, "alice smith");
    String titleThenUpper = orchestrator.normalize(List.of("titlecase", "upper"), FIELD, "alice smith");
    assertThat(upperThenTitle).isNotEqualTo(titleThenUpper);
  }

  @Test
  void each_call_runs_on_an_isolated_cell_not_a_shared_one() {
    assertThat(orchestrator.normalize(List.of("lower"), FIELD, "ALICE")).isEqualTo("alice");
    assertThat(orchestrator.normalize(List.of("lower"), FIELD, "BOB")).isEqualTo("bob");
  }

  @Test
  void a_null_raw_value_normalizes_to_null() {
    assertThat(orchestrator.normalize(List.of("lower", "clean"), FIELD, null)).isNull();
  }

  @Test
  void refuses_a_hook_outside_the_allowlist() {
    assertThatThrownBy(() -> orchestrator.normalize(List.of("mask"), FIELD, "alice@example.com"))
        .isInstanceOf(NormalizerNotAdmittedException.class)
        .hasMessageContaining("mask");
  }

  @Test
  void refuses_a_hook_unknown_to_the_catalog() {
    assertThatThrownBy(() -> orchestrator.normalize(List.of("does-not-exist"), FIELD, "x"))
        .isInstanceOf(NormalizerNotAdmittedException.class)
        .hasMessageContaining("does-not-exist");
  }
}
