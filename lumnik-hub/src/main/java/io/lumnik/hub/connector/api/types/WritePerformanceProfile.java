package io.lumnik.hub.connector.api.types;

public sealed interface WritePerformanceProfile {
  record Safe()     implements WritePerformanceProfile {}
  record Bulk()     implements WritePerformanceProfile {}
  record BulkLoad() implements WritePerformanceProfile {}
}
