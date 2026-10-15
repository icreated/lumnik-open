package io.lumnik.hub.export.run;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DueRuleTest {

  static final String DAILY_4AM = "0 4 * * *";

  @Test
  void aSlotBeforeCreationIsNotOwed() {
    Instant created = Instant.parse("2026-09-24T05:00:00Z");
    assertThat(DueRule.occurrence(DAILY_4AM, created, Instant.parse("2026-09-24T06:00:00Z"))).isEmpty();
  }

  @Test
  void afterAGapOnlyTheLatestSlotIsOwed() {
    Instant created = Instant.parse("2026-09-01T00:00:00Z");
    assertThat(DueRule.occurrence(DAILY_4AM, created, Instant.parse("2026-09-24T12:34:56Z")))
        .contains(Instant.parse("2026-09-24T04:00:00Z"));
  }

  @Test
  void nowExactlyOnTheSlotOwesThatSlot() {
    Instant created = Instant.parse("2026-09-01T00:00:00Z");
    Instant slot = Instant.parse("2026-09-24T04:00:00Z");
    assertThat(DueRule.occurrence(DAILY_4AM, created, slot)).contains(slot);
  }

  @Test
  void aSlotAtCreationIsOwed() {
    Instant slot = Instant.parse("2026-09-24T04:00:00Z");
    assertThat(DueRule.occurrence(DAILY_4AM, slot, Instant.parse("2026-09-24T04:00:30Z"))).contains(slot);
  }
}
