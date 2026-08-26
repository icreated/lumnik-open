package io.lumnik.hub.connector.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lumnik.hub.connector.api.types.ConnectorCursor;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Composite watermark cursor for jdbc-generic.
 *
 * <p>JSON shape: {"type":"JdbcWatermark","col":"updated",
 *                  "value":"2026-05-23T10:00:00Z","pk":{"id":42}}
 *
 * <p>On resume we read rows with: WHERE col > value OR (col = value AND pk > lastPk)
 * to ensure stable pagination across ties in the watermark column.
 */
public record JdbcCursor(String col, Object value, Map<String, Object> pk) {

  private static final ObjectMapper M = new ObjectMapper();
  private static final String TYPE_TAG = "JdbcWatermark";
  private static final String VALUE_KEY = "value";

  public static JdbcCursor empty() {
    return new JdbcCursor("", null, Collections.emptyMap());
  }

  public ConnectorCursor toConnectorCursor() {
    ObjectNode node = M.createObjectNode();
    node.put("type", TYPE_TAG);
    node.put("col", col);
    if (value != null) node.putPOJO(VALUE_KEY, value);
    node.putPOJO("pk", pk);
    return new ConnectorCursor(node, Instant.now());
  }

  public static JdbcCursor fromConnectorCursor(ConnectorCursor cc) {
    if (cc == null || cc.isEmpty()) return empty();
    JsonNode v = cc.value();
    String col = v.has("col") ? v.get("col").asText("") : "";
    Object value = v.has(VALUE_KEY) && !v.get(VALUE_KEY).isNull()
        ? M.convertValue(v.get(VALUE_KEY), Object.class) : null;
    Map<String, Object> pk = v.has("pk") && !v.get("pk").isNull()
        ? M.convertValue(v.get("pk"), Map.class)
        : Collections.emptyMap();
    return new JdbcCursor(col, value, new LinkedHashMap<>(pk));
  }
}
