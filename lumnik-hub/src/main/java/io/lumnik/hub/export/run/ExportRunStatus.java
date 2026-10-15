package io.lumnik.hub.export.run;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

/**
 * The persisted status of a {@code connector.export_run} row. {@link #sqlValue()} is the single
 * spelling of each value, so Java and the column never drift apart. The V54 {@code CHECK}
 * constraint holds the same four values (V68 added Queued).
 */
public enum ExportRunStatus {
  /** Accepted, not started (#952). */
  QUEUED("Queued"),
  RUNNING("Running"),
  /** Delivered: the V54 {@code CHECK} guarantees the full receipt sits next to it. */
  COMPLETED("Completed"),
  FAILED("Failed");

  private final String sql;

  ExportRunStatus(String sql) {
    this.sql = sql;
  }

  /** Also the REST spelling, so an export run reads like a connector run ({@code "Completed"}). */
  @JsonValue
  public String sqlValue() {
    return sql;
  }

  /** The inverse of {@link #sqlValue()}: reads a {@code status} column back. */
  public static ExportRunStatus fromSql(String value) {
    return Arrays.stream(values())
        .filter(s -> s.sql.equals(value))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("unknown export_run.status: " + value));
  }
}
