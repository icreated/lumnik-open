package io.lumnik.hub.connector.api.types;

import java.util.List;

public sealed interface ReadResult<T> {
  record HasMore<T>(List<T> records, ConnectorCursor nextCursor)  implements ReadResult<T> {}
  record EndOfStream<T>(List<T> records)                          implements ReadResult<T> {}
  record EmptyButOpen<T>(ConnectorCursor sameCursor)              implements ReadResult<T> {}
}
