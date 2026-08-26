package io.lumnik.hub.connector.api;

import io.lumnik.hub.connector.api.types.*;
import java.util.Map;
import java.util.Set;

@SuppressWarnings("java:S1452") // wildcards are intentional in this SPI seam — callers work through the erasure
public interface ConnectorType {
  String id();

  WritePerformanceProfile defaultWriteProfile();

  Set<TriggerMode> supportedTriggerModes();

  void validateConfig(Map<String, Object> config);

  default Schema discover(ConnectorContext ctx) {
    return null;
  }

  ItemReader<?> reader(ConnectorContext ctx);

  default ItemWriter<?> writer(ConnectorContext ctx) {
    return null;
  }

  default ItemProcessor<?, ?> defaultProcessor(ConnectorContext ctx) {
    return ItemProcessor.identity();
  }

  default int defaultChunkSize() {
    return 500;
  }
}
