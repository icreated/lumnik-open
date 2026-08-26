package io.lumnik.hub.connector.api;

import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.MalformedRecord;
import io.lumnik.hub.connector.api.types.ReadResult;

import java.util.List;

public interface ItemReader<T> {
  ReadResult<T> read(int chunkSize, ConnectorCursor cursor, ConnectorContext ctx);

  /**
   * Malformed source rows isolated during the most recent {@link #read} pass. The engine drains
   * these after a successful read and routes them to the dead-letter queue. Default: none.
   */
  default List<MalformedRecord> drainMalformed() { return List.of(); }

  default void close() {}
}
