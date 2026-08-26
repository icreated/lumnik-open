package io.lumnik.hub.connector.api.types;

public sealed interface SyncMode {
  record Incremental() implements SyncMode {}
  record FullRefresh() implements SyncMode {}
  record Snapshot()    implements SyncMode {}
}
