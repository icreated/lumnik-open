package io.lumnik.hub.connector.api.types;

/**
 * What a connector type does once a run's stream has ended, before the run is reported done (#1195):
 * nothing, rows removed because the source no longer holds them, or a refusal that fails the run.
 */
public sealed interface StreamEnd {

  /** {@code rows} removed from the hub because the source no longer holds them. */
  record Removed(long rows) implements StreamEnd {}

  /**
   * The run fails with {@code reason}, already worded for the operator, and nothing was removed. The
   * run's stored position is cleared, so the next run reads the whole source again rather than
   * skipping a source it was refused on.
   */
  record Refused(String reason) implements StreamEnd {}
}
