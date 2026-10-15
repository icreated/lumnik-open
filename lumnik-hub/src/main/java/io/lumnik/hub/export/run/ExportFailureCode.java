package io.lumnik.hub.export.run;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Locale;

/**
 * Why a {@code connector.export_run} row is {@code Failed}. The column value is the lowercase name;
 * the V54 {@code CHECK} constraint holds the same five values.
 */
public enum ExportFailureCode {
  /** The stored definition no longer resolves (entity gone, destination misconfigured). */
  DEFINITION,
  GENERATION,
  PUBLICATION,
  /** The target name already exists remotely and the destination refused to overwrite it. */
  COLLISION,
  /** The run was left {@code Running} by a dead process and reaped. */
  INTERRUPTED;

  private final String sql = name().toLowerCase(Locale.ROOT);

  /** Also the REST spelling: the REST value is the column value ({@code "definition"}). */
  @JsonValue
  public String sqlValue() {
    return sql;
  }

  /** The inverse of {@link #sqlValue()}: reads a {@code failure_code} column back. */
  public static ExportFailureCode fromSql(String value) {
    return Arrays.stream(values())
        .filter(c -> c.sql.equals(value))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("unknown export_run.failure_code: " + value));
  }
}
