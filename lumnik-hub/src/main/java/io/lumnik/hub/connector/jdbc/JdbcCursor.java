package io.lumnik.hub.connector.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lumnik.hub.connector.api.types.ConnectorCursor;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Composite watermark cursor for jdbc-generic.
 *
 * <p>JSON shape: {"type":"JdbcWatermark","col":"updated","value":"2026-05-23T10:00:00Z",
 *                  "keys":["order_id","line_no"],"pk":{"order_id":72,"line_no":3}}
 *
 * <p>{@code pk} carries every declared primary-key column, in declaration order — a composite key
 * that stored only its first column would resume at the next distinct value of that column and skip
 * the rest of the group the chunk stopped inside.
 *
 * <p>{@code keys} lists those columns as an ARRAY, and exists for one reason: the cursor is stored
 * in a {@code jsonb} column, and jsonb does not preserve the key order of an object — it reorders
 * them shortest-first, so {@code {order_id, line_no}} comes back as {@code {line_no, order_id}}.
 * A jsonb array does keep its order, so {@code keys} is what restores {@code pk}'s order on the way
 * back in. Without it the reader compares the cursor's key order against its own {@code pk_columns},
 * finds them different, and declares its OWN cursor foreign on every chunk — a table that restarts
 * for ever (found live: 2.3 million rows read on a 700-row table). Cursors written before this field
 * existed have none; they are single-column, so their order is not in question.
 *
 * <p>On resume we read rows with: {@code WHERE (col, pk...) > (value, lastPk...)} — one
 * lexicographic comparison, so ties in the watermark column are broken by the whole key.
 */
public record JdbcCursor(String col, Object value, Map<String, Object> pk) {

  private static final ObjectMapper M = new ObjectMapper();
  private static final String TYPE_TAG = "JdbcWatermark";
  private static final String VALUE_KEY = "value";
  private static final String KEYS_KEY = "keys";

  /**
   * Rebuilds {@code pk} in the order {@code keys} declares — the order jsonb dropped. A column named
   * in {@code keys} but absent from {@code pk} is skipped, and any column {@code keys} does not name
   * follows in the order the store returned it: the reader compares the result against its own
   * {@code pk_columns} and restarts the table when they differ, so a half-written cursor is refused
   * rather than obeyed.
   */
  private static Map<String, Object> inKeyOrder(Map<String, Object> pk, JsonNode keys) {
    Map<String, Object> ordered = new LinkedHashMap<>();
    if (keys != null && keys.isArray()) {
      for (JsonNode k : keys) {
        String name = k.asText();
        if (pk.containsKey(name)) ordered.put(name, pk.get(name));
      }
    }
    pk.forEach(ordered::putIfAbsent);
    return ordered;
  }

  public static JdbcCursor empty() {
    return new JdbcCursor("", null, Collections.emptyMap());
  }

  public ConnectorCursor toConnectorCursor() {
    ObjectNode node = M.createObjectNode();
    node.put("type", TYPE_TAG);
    node.put("col", col);
    if (value != null) node.putPOJO(VALUE_KEY, value);
    node.putPOJO(KEYS_KEY, List.copyOf(pk.keySet()));
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
    return new JdbcCursor(col, value, inKeyOrder(pk, v.get(KEYS_KEY)));
  }
}
