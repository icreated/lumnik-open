package io.lumnik.hub.fidelity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TierTest {

  private static List<Finding> high() {
    return List.of(new Finding(Family.DECEPTION, Severity.HIGH, 1L, "x"));
  }

  @Test
  void high_finding_fails_silver_but_passes_bronze() {
    assertThat(Verdict.aggregate(Tier.SILVER, high()).passed()).isFalse();
    assertThat(Verdict.aggregate(Tier.BRONZE, high()).passed()).isTrue();
  }

  @Test
  void empty_passes_every_tier_including_gold() {
    assertThat(Verdict.aggregate(Tier.GOLD, List.of()).passed()).isTrue();
  }

  @Test
  void a_low_finding_fails_gold_but_passes_silver() {
    List<Finding> low = List.of(new Finding(Family.FALSE_BLOCK, Severity.LOW, 2L, "y"));
    assertThat(Verdict.aggregate(Tier.GOLD, low).passed()).isFalse();
    assertThat(Verdict.aggregate(Tier.SILVER, low).passed()).isTrue();
  }
}
