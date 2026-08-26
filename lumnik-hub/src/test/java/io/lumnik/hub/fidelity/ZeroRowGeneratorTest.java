package io.lumnik.hub.fidelity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ZeroRowGeneratorTest {

  @Test
  void same_seed_yields_an_identical_scenario() {
    assertThat(ZeroRowGenerator.generate(7L)).isEqualTo(ZeroRowGenerator.generate(7L));
  }

  @Test
  void the_seed_range_covers_both_empty_and_non_empty() {
    boolean sawEmpty = false;
    boolean sawNonEmpty = false;
    for (long s = 0; s < 200; s++) {
      boolean e = ZeroRowGenerator.generate(s).expectEmpty();
      sawEmpty |= e;
      sawNonEmpty |= !e;
    }
    assertThat(sawEmpty).isTrue();
    assertThat(sawNonEmpty).isTrue();
  }

  @Test
  void empty_scenarios_filter_on_an_absent_value() {
    for (long s = 0; s < 100; s++) {
      ZeroRowScenario sc = ZeroRowGenerator.generate(s);
      if (sc.expectEmpty()) {
        assertThat(sc.sql()).containsAnyOf("'X'", "'Y'", "'Z'");
      } else {
        assertThat(sc.sql()).containsAnyOf("'A'", "'B'", "'C'");
      }
    }
  }
}
