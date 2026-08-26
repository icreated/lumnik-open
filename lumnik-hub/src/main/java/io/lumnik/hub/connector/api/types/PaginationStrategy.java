package io.lumnik.hub.connector.api.types;

import java.time.Instant;

public sealed interface PaginationStrategy {
  record Offset(long offset, int pageSize)                     implements PaginationStrategy {}
  record Keyset(String keyField, Object lastKey, int pageSize) implements PaginationStrategy {}
  record Token(String continuationToken)                       implements PaginationStrategy {}
  record Timestamp(Instant watermark)                          implements PaginationStrategy {}
  record Hybrid(Instant watermark, Object lastKey)             implements PaginationStrategy {}
}
