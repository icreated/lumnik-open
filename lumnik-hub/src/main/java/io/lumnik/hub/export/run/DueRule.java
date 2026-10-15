package io.lumnik.hub.export.run;

import com.cronutils.model.time.ExecutionTime;
import io.lumnik.hub.validator.common.CronField;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Which schedule occurrence an export owes at {@code now}: the latest cron slot at or before it
 * (UNIX 5-field dialect, UTC — the dialect CronField validates at apply, as ConnectorScheduler
 * reads it). Only the latest: after a gap the missed slots are not back-filled. A slot before the
 * export existed is never owed.
 */
public final class DueRule {

  private DueRule() {}

  public static Optional<Instant> occurrence(String cron, Instant createdAt, Instant now) {
    ExecutionTime et = ExecutionTime.forCron(CronField.parse(cron));
    ZonedDateTime at = now.atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES);
    // Slots are whole minutes (and isMatch ignores seconds). lastExecution is strictly before
    // its argument, so a slot exactly at the truncated now is asked for separately.
    if (et.isMatch(at)) return Optional.of(at.toInstant()).filter(o -> !o.isBefore(createdAt));
    return et.lastExecution(at)
        .map(ZonedDateTime::toInstant)
        .filter(o -> !o.isBefore(createdAt));
  }
}
