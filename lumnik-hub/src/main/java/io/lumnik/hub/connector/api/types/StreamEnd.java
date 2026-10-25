package io.lumnik.hub.connector.api.types;

/**
 * What a connector type does once a run's stream has ended, before the run is reported done (#1195):
 * nothing, rows removed because the source no longer holds them, or a refusal that fails the run.
 */
public sealed interface StreamEnd {

  /**
   * {@code rows} removed from the hub because the source no longer holds them. A csv full refresh does not
   * count the older version of a row whose values changed: the source still holds that row (#1544).
   */
  record Removed(long rows) implements StreamEnd {}

  /**
   * {@code files} not read because they had not changed since they were read to the end (#1371). A file
   * not read removes nothing.
   */
  record Unchanged(long files) implements StreamEnd {}

  /**
   * {@code files} read and committed, and not moved or removed as {@code after_process} declares (#1382): they
   * stay in the source folder.
   */
  record NotArchived(long files) implements StreamEnd {}

  /**
   * The run fails with {@code reason}, already worded for the operator, and nothing was removed. The
   * run's stored position is cleared, so the next run reads the whole source again rather than
   * skipping a source it was refused on.
   */
  record Refused(String reason) implements StreamEnd {}
}
