package io.lumnik.hub.connector.api.types;

import java.util.List;

/**
 * What one {@code ItemReader.read} returns. The engine persists a position only from what the
 * result hands over: {@link HasMore#nextCursor}, {@link EndOfStream#finalCursor} or {@link
 * EmptyButOpen#position}. A bare {@link EndOfStream} carries none, so a reader that ends on a
 * LOADED bare EndOfStream forfeits the position of those records — the next run re-reads them. End
 * on the read that finds nothing (jdbc, rest, the sftp/s3 csv reader, kafka and the hello-world
 * reference do), or end loaded with a final cursor (the local csv glob does).
 */
public sealed interface ReadResult<T> {
  record HasMore<T>(List<T> records, ConnectorCursor nextCursor)  implements ReadResult<T> {}
  /**
   * Terminal. The records here are covered by a persisted cursor only when {@code finalCursor} is
   * not empty; the one-argument form is positionless, as it always was.
   */
  record EndOfStream<T>(List<T> records, ConnectorCursor finalCursor) implements ReadResult<T> {
    public EndOfStream(List<T> records) {
      this(records, ConnectorCursor.empty());
    }
  }
  /**
   * Nothing usable this pass and the source is NOT dry: the reader stopped for a reason of its own
   * while the run's {@link RunBound} says there is more. Its {@code position} IS persisted — a
   * reader reaches here having advanced past records it dead-lettered, and dropping that advance
   * would re-dead-letter them on every future run.
   *
   * <p>Named {@code position} and not {@code sameCursor}: on the path that produces this, the
   * cursor has moved.
   */
  record EmptyButOpen<T>(ConnectorCursor position)                implements ReadResult<T> {}
}
