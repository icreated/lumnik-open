package io.lumnik.hub.connector.domain;

import java.util.Arrays;

/**
 * The persisted status of a {@code connector.connector_run} row. {@link #sqlValue()} is the single
 * spelling of each value, so Java and the column never drift apart. The V53 {@code CHECK}
 * constraint holds the same six values (V68 added Queued). This is distinct from the in-JVM {@code RunStatus} future
 * type, which carries a run's result, not its stored state.
 */
public enum ConnectorRunStatus {
  /** Accepted, not started: it holds no lock and has written nothing. Any replica's dispatcher may start it (#952). */
  QUEUED("Queued"),
  RUNNING("Running"),
  COMPLETED("Completed"),
  /** Wrote everything it read but stopped below its target. The next run resumes from its position. */
  PARTIAL("Partial"),
  FAILED("Failed"),
  CANCELLED("Cancelled");

  private final String sql;

  ConnectorRunStatus(String sql) {
    this.sql = sql;
  }

  public String sqlValue() {
    return sql;
  }

  /** Every status except {@link #QUEUED} and {@link #RUNNING} is terminal: once there, a run row never moves again. */
  public boolean isTerminal() {
    return this != QUEUED && this != RUNNING;
  }

  /** Whether a {@code status} column value is {@link #RUNNING} — the one check every reader makes. */
  public static boolean isRunning(String value) {
    return RUNNING.sql.equals(value);
  }

  /** The inverse of {@link #sqlValue()}: reads a {@code status} column back. */
  public static ConnectorRunStatus fromSql(String value) {
    return Arrays.stream(values())
        .filter(s -> s.sql.equals(value))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("unknown connector_run.status: " + value));
  }
}
