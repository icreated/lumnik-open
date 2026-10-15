package io.lumnik.hub.entity.resolution;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lot #616's own acceptance bar (design doc §5): "the allowlist is established by a test, not by a
 * reading — one property per candidate hook: two distinct inputs converge only where that is the
 * declared equivalence, and a failure never produces a constant." One test per hook in {@link
 * NormalizerAllowlist#ADMITTED}, run through the real {@link NormalizationOrchestrator} — the same
 * seam a resolution rule would use, not the raw {@code CellHook}.
 *
 * <p>Seeded {@code Random}, not a property-test library: deterministic, no new dependency for one
 * lot (Kalashnikov principle) — every seed below is fixed so a run never flakes.
 */
@QuarkusTest
class NormalizationOrchestratorPropertyTest {

  @Inject NormalizationOrchestrator orchestrator;

  private static final String FIELD = "value";
  private static final int SAMPLES = 200;

  /**
   * The "no constant" half of the property: a hook that is safe to admit must not collapse a large
   * set of distinct random inputs onto one output — that is exactly the false-merge shape the
   * allowlist exists to keep out.
   */
  private void assertNoConstantCollapse(String hookId, List<String> samples) {
    Set<String> outputs = samples.stream()
        .map(v -> orchestrator.normalize(List.of(hookId), FIELD, v))
        .collect(Collectors.toCollection(LinkedHashSet::new));
    assertThat(outputs)
        .as("%s must not collapse %d distinct random inputs onto a single constant",
            hookId, samples.size())
        .hasSizeGreaterThan(1);
  }

  @Test
  void every_admitted_hook_has_a_no_constant_collapse_property() {
    long seed = 0;
    for (String hookId : NormalizerAllowlist.ADMITTED.keySet()) {
      assertNoConstantCollapse(hookId, RandomLetterStrings.of(seed++, SAMPLES));
    }
  }

  // Declared-equivalence pins: a specific pair that MUST converge, and one that must not — the
  // "only the intended equivalence collapses" half of the property, per hook family.

  @Test
  void lower_and_upper_converge_only_on_case() {
    assertThat(orchestrator.normalize(List.of("lower"), FIELD, "Alice"))
        .isEqualTo(orchestrator.normalize(List.of("lower"), FIELD, "ALICE"))
        .isNotEqualTo(orchestrator.normalize(List.of("lower"), FIELD, "Bob"));
    assertThat(orchestrator.normalize(List.of("upper"), FIELD, "Alice"))
        .isEqualTo(orchestrator.normalize(List.of("upper"), FIELD, "alice"))
        .isNotEqualTo(orchestrator.normalize(List.of("upper"), FIELD, "bob"));
  }

  @Test
  void titlecase_converges_only_on_case_and_whitespace_run_length() {
    assertThat(orchestrator.normalize(List.of("titlecase"), FIELD, "alice smith"))
        .isEqualTo(orchestrator.normalize(List.of("titlecase"), FIELD, "ALICE   SMITH"))
        .isNotEqualTo(orchestrator.normalize(List.of("titlecase"), FIELD, "bob jones"));
  }

  @Test
  void remove_diacritics_converges_accented_and_plain_forms() {
    assertThat(orchestrator.normalize(List.of("remove-diacritics"), FIELD, "Café"))
        .isEqualTo(orchestrator.normalize(List.of("remove-diacritics"), FIELD, "Cafe"))
        .isNotEqualTo(orchestrator.normalize(List.of("remove-diacritics"), FIELD, "Cafeteria"));
  }

  @Test
  void strip_invisible_converges_only_when_the_only_difference_is_invisible_characters() {
    assertThat(orchestrator.normalize(List.of("strip-invisible"), FIELD, "Al​ice"))
        .isEqualTo(orchestrator.normalize(List.of("strip-invisible"), FIELD, "Alice"))
        .isNotEqualTo(orchestrator.normalize(List.of("strip-invisible"), FIELD, "Bob"));
  }

  @Test
  void replace_smart_quotes_converges_curly_and_straight_forms() {
    assertThat(orchestrator.normalize(List.of("replace-smart-quotes"), FIELD, "O’Brien"))
        .isEqualTo(orchestrator.normalize(List.of("replace-smart-quotes"), FIELD, "O'Brien"))
        .isNotEqualTo(orchestrator.normalize(List.of("replace-smart-quotes"), FIELD, "O'Neil"));
  }

  @Test
  void decode_html_entities_converges_encoded_and_plain_forms() {
    assertThat(orchestrator.normalize(List.of("decode-html-entities"), FIELD, "Coffee &amp; cream"))
        .isEqualTo(orchestrator.normalize(List.of("decode-html-entities"), FIELD, "Coffee & cream"))
        .isNotEqualTo(orchestrator.normalize(List.of("decode-html-entities"), FIELD, "Coffee & sugar"));
  }

  @Test
  void clean_converges_only_on_a_leading_bom() {
    assertThat(orchestrator.normalize(List.of("clean"), FIELD, "﻿Alice"))
        .isEqualTo(orchestrator.normalize(List.of("clean"), FIELD, "Alice"))
        .isNotEqualTo(orchestrator.normalize(List.of("clean"), FIELD, "Bob"));
  }

  @Test
  void fix_mojibake_converges_only_on_the_mojibake_signature() {
    assertThat(orchestrator.normalize(List.of("fix-mojibake"), FIELD, "BÃ©nÃ©dict"))
        .isEqualTo(orchestrator.normalize(List.of("fix-mojibake"), FIELD, "Bénédict"))
        .isNotEqualTo(orchestrator.normalize(List.of("fix-mojibake"), FIELD, "Bénéfice"));
  }
}
