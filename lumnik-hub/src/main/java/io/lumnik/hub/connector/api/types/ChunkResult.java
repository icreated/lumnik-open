package io.lumnik.hub.connector.api.types;

import java.util.List;

public sealed interface ChunkResult {
  record Success(int processed, ConnectorCursor nextCursor)                   implements ChunkResult {}
  record PartialSuccess(int processed, int skipped, List<SkipReason> reasons,
                        ConnectorCursor nextCursor)                           implements ChunkResult {}
  record Failure(String message, Throwable cause, boolean retryable)         implements ChunkResult {}
}
