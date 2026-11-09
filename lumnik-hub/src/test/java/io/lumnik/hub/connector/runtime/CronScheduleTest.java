package io.lumnik.hub.connector.runtime;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link ConnectorScheduler#isCronDue} — the cron due-ness decision,
 * with no DB or Quarkus. A connector is due when the cron's last firing instant at/before
 * {@code now} is strictly after the reference (last run, or creation time).
 */
class CronScheduleTest {

  private static final ZonedDateTime NOW =
      ZonedDateTime.of(2026, 6, 1, 10, 30, 45, 0, ZoneOffset.UTC);

  @Test
  void everyMinute_due_when_last_run_is_in_a_previous_minute() {
    Instant ranTwoMinutesAgo = NOW.toInstant().minusSeconds(120);
    assertThat(ConnectorScheduler.isCronDue("* * * * *", ranTwoMinutesAgo, NOW)).isTrue();
  }

  @Test
  void everyMinute_not_due_when_already_ran_this_minute() {
    Instant ranThisMinute = ZonedDateTime.of(2026, 6, 1, 10, 30, 30, 0, ZoneOffset.UTC).toInstant();
    assertThat(ConnectorScheduler.isCronDue("* * * * *", ranThisMinute, NOW)).isFalse();
  }

  @Test
  void daily_due_when_last_run_was_days_ago() {
    Instant twoDaysAgo = NOW.toInstant().minusSeconds(2 * 86400);
    assertThat(ConnectorScheduler.isCronDue("0 0 * * *", twoDaysAgo, NOW)).isTrue();
  }

  @Test
  void daily_not_due_when_already_ran_after_last_midnight() {
    Instant ranThisMorning = ZonedDateTime.of(2026, 6, 1, 9, 0, 0, 0, ZoneOffset.UTC).toInstant();
    assertThat(ConnectorScheduler.isCronDue("0 0 * * *", ranThisMorning, NOW)).isFalse();
  }

  @Test
  void invalid_cron_is_never_due() {
    assertThat(ConnectorScheduler.isCronDue("not-a-cron", Instant.EPOCH, NOW)).isFalse();
  }

  /** #1922: the slot is fenced by the window it fell in, not by the window the tick finds. */
  @Test
  void a_slot_that_fell_outside_the_window_is_not_in_it_even_when_the_tick_is_inside() {
    ZonedDateTime windowOpen = ZonedDateTime.of(2026, 6, 1, 22, 0, 30, 0, ZoneOffset.UTC);
    assertThat(ConnectorScheduler.slotInWindow("0 6 * * *", "22:00-06:00", windowOpen))
        .as("06:00 is the exclusive end: the slot is skipped, not run at 22:00")
        .isFalse();
  }

  @Test
  void a_slot_inside_the_window_is_in_it() {
    ZonedDateTime tick = ZonedDateTime.of(2026, 6, 1, 6, 0, 30, 0, ZoneOffset.UTC);
    assertThat(ConnectorScheduler.slotInWindow("0 6 * * *", "22:00-07:00", tick)).isTrue();
  }

  @Test
  void without_a_window_every_slot_is_in_it() {
    assertThat(ConnectorScheduler.slotInWindow("0 6 * * *", null, NOW)).isTrue();
  }

  @Test
  void a_slot_at_the_window_start_is_in_it() {
    ZonedDateTime tick = ZonedDateTime.of(2026, 6, 1, 22, 0, 30, 0, ZoneOffset.UTC);
    assertThat(ConnectorScheduler.slotInWindow("0 22 * * *", "22:00-06:00", tick))
        .as("the start is inclusive")
        .isTrue();
  }
}
