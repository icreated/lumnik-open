package io.lumnik.hub.connector.api.types;

import java.util.List;

public sealed interface ChunkResult {
  /**
   * @param endOfStream the read was TERMINAL — the source has nothing more, whatever {@code
   *     processed} says. A reader may end holding records and persist no position (the local csv
   *     glob does; see {@link ReadResult}), so "records were read" does not mean "call me again".
   *     The engine used to infer this by comparing {@code nextCursor} with the cursor it had handed
   *     in — an inference that only worked because this class passed the SAME cursor object back on
   *     that path, and that hid a stuck endpoint behind the same test.
   */
  record Success(int processed, ConnectorCursor nextCursor, boolean endOfStream) implements ChunkResult {}
  record PartialSuccess(int processed, int skipped, List<SkipReason> reasons,
                        ConnectorCursor nextCursor)                           implements ChunkResult {}
  /**
   * The reader stopped short of the run's target. Not a failure — nothing went wrong and everything
   * read was written — and not a {@code Success}, whose {@code processed == 0} tells the engine the
   * source is dry and the run may report itself complete.
   *
   * <p>Deliberately its own member rather than a flag on {@code Success}: a flag that answers both
   * "is the source dry?" and "did we reach the target?" ends up lying to one of them. And not
   * {@code PartialSuccess}, which is a shortfall in DELIVERY (rows read, rows dropped) where this is
   * a shortfall in POSITION.
   *
   * <p>It carries the detail and nothing else. A {@code processed} count and a {@code nextCursor}
   * were the first shape and both were write-only: the position is persisted by the executor before
   * this is returned, and the engine reads only the sentence. Worse than decorative — a later
   * producer that HAD processed rows would have seen them silently dropped from the run's
   * accounting, since this ending returns before the counters.
   *
   * @param detail what the operator reads on the run row, naming the target and where it stopped
   */
  record ShortOfTarget(String detail) implements ChunkResult {}

  /**
   * The reader stopped inside the chunk because the run was cancelled (#1444): nothing it read was written, and
   * the run ends {@code Cancelled} as the engine's own check between chunks ends it. Its own member, not a
   * {@code Failure} the engine would have to recognise by its cause.
   */
  record Cancelled() implements ChunkResult {}

  record Failure(String message, Throwable cause, boolean retryable)         implements ChunkResult {}
}
