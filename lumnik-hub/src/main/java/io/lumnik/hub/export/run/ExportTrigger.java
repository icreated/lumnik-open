package io.lumnik.hub.export.run;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Locale;

/** What started a {@code connector.export_run}: the V54 {@code trigger} {@code CHECK}'s two values. */
public enum ExportTrigger {
  MANUAL,
  /** A due cron slot; the only trigger that carries an {@code occurrence}. */
  SCHEDULE;

  private final String sql = name().toLowerCase(Locale.ROOT);

  /** Also the REST spelling: the REST value is the column value ({@code "schedule"}). */
  @JsonValue
  public String sqlValue() {
    return sql;
  }

  public static ExportTrigger fromSql(String value) {
    return Arrays.stream(values())
        .filter(t -> t.sql.equals(value))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("unknown export_run.trigger: " + value));
  }
}
