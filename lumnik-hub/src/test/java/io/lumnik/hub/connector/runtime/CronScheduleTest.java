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
}
