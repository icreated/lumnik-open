package io.lumnik.hub.connector.api.types;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import java.time.Instant;

public record ConnectorCursor(JsonNode value, Instant capturedAt) {

  public static ConnectorCursor empty() {
    return new ConnectorCursor(NullNode.instance, Instant.now());
  }

  public boolean isEmpty() {
    return value == null || value.isNull();
  }
}
