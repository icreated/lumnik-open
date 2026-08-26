package io.lumnik.hub.fidelity;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AbstentionGeneratorTest {

  @Test
  void same_seed_yields_an_identical_scenario() {
    assertThat(AbstentionGenerator.generate(7L)).isEqualTo(AbstentionGenerator.generate(7L));
  }

  @Test
  void the_seed_range_covers_every_kind() {
    Set<AbstentionKind> seen = EnumSet.noneOf(AbstentionKind.class);
    for (long s = 0; s < 200; s++) seen.add(AbstentionGenerator.generate(s).kind());
    assertThat(seen).containsExactlyInAnyOrder(AbstentionKind.values());
  }

  @Test
  void expect_abstain_matches_the_kind() {
    for (long s = 0; s < 100; s++) {
      AbstentionScenario sc = AbstentionGenerator.generate(s);
      assertThat(sc.expectAbstain()).isEqualTo(sc.kind().expectAbstain);
    }
  }
}
