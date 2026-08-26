package io.lumnik.hub.fidelity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FidelityGeneratorTest {

  @Test
  void same_seed_yields_an_identical_scenario() {
    assertThat(FidelityGenerator.generate(42L)).isEqualTo(FidelityGenerator.generate(42L));
  }

  @Test
  void the_seed_range_covers_both_refusal_modes() {
    boolean sawRefuse = false;
    boolean sawPass = false;
    for (long s = 0; s < 200; s++) {
      boolean e = FidelityGenerator.generate(s).expectRefusal();
      sawRefuse |= e;
      sawPass |= !e;
    }
    assertThat(sawRefuse).as("some scenarios must expect a refusal").isTrue();
    assertThat(sawPass).as("some scenarios must expect to pass").isTrue();
  }

  @Test
  void expect_refusal_matches_the_kind() {
    for (long s = 0; s < 100; s++) {
      Scenario sc = FidelityGenerator.generate(s);
      assertThat(sc.expectRefusal()).isEqualTo(sc.kind().expectRefusal);
    }
  }
}
