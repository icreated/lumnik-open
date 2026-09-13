package io.lumnik.hub.connector.api.types;

import java.util.List;

/**
 * What one {@code ItemReader.read} returns. The engine persists a position ONLY from
 * {@link HasMore#nextCursor}: an {@link EndOfStream} carries none, so a reader that ends on a
 * LOADED EndOfStream forfeits the position of those records — the next run re-reads them. End on
 * the read that finds nothing (jdbc, rest, the sftp/s3 csv reader, kafka and the hello-world
 * reference do); the local csv glob, which documents the re-read, still ends loaded.
 */
public sealed interface ReadResult<T> {
  record HasMore<T>(List<T> records, ConnectorCursor nextCursor)  implements ReadResult<T> {}
  /** Terminal, positionless: the records here are never covered by a persisted cursor. */
  record EndOfStream<T>(List<T> records)                          implements ReadResult<T> {}
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
