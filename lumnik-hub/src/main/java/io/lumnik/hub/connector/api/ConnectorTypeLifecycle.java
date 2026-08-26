package io.lumnik.hub.connector.api;

import io.lumnik.hub.connector.api.types.ChunkResult;
import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.RunStatus;

public interface ConnectorTypeLifecycle {
  default void onRunStart(ConnectorContext ctx) {}

  default void onRunEnd(ConnectorContext ctx, RunStatus status) {}

  default void onChunkStart(ConnectorContext ctx, int chunkIndex) {}

  default void onChunkEnd(ConnectorContext ctx, ChunkResult result) {}
}
