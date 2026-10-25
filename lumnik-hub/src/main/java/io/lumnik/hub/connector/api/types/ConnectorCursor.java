package io.lumnik.hub.connector.api.types;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import java.time.Instant;

/**
 * A stored position. {@code mappingCarry} is what a stateful mapping carried after that position (#1452), read with it
 * from the same cursor row; a reader neither reads nor sets it — the cursors it hands back carry none.
 */
public record ConnectorCursor(JsonNode value, Instant capturedAt, JsonNode mappingCarry) {

  public ConnectorCursor(JsonNode value, Instant capturedAt) {
    this(value, capturedAt, null);
  }

  public static ConnectorCursor empty() {
    return new ConnectorCursor(NullNode.instance, Instant.now());
  }

  public boolean isEmpty() {
    return value == null || value.isNull();
  }
}
