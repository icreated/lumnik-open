package io.lumnik.hub.connector.api.types;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;

public final class Cursors {
  private static final ObjectMapper M = new ObjectMapper().findAndRegisterModules();

  private static final String LAST_KEY = "lastKey";
  private static final String PAGE_SIZE = "pageSize";
  private static final String WATERMARK = "watermark";

  private Cursors() {}

  public static ConnectorCursor empty() { return ConnectorCursor.empty(); }

  public static ConnectorCursor offset(long offset, int pageSize) {
    ObjectNode n = M.createObjectNode();
    n.put("type", "Offset").put("offset", offset).put(PAGE_SIZE, pageSize);
    return new ConnectorCursor(n, Instant.now());
  }

  public static ConnectorCursor keyset(String keyField, Object lastKey) {
    ObjectNode n = M.createObjectNode();
    n.put("type", "Keyset").put("keyField", keyField);
    n.set(LAST_KEY, M.valueToTree(lastKey));
    n.put(PAGE_SIZE, 0);
    return new ConnectorCursor(n, Instant.now());
  }

  public static ConnectorCursor token(String continuation) {
    ObjectNode n = M.createObjectNode();
    n.put("type", "Token").put("continuationToken", continuation);
    return new ConnectorCursor(n, Instant.now());
  }

  public static ConnectorCursor timestamp(Instant watermark) {
    ObjectNode n = M.createObjectNode();
    n.put("type", "Timestamp").put(WATERMARK, watermark.toString());
    return new ConnectorCursor(n, Instant.now());
  }

  public static ConnectorCursor hybrid(Instant watermark, Object lastKey) {
    ObjectNode n = M.createObjectNode();
    n.put("type", "Hybrid").put(WATERMARK, watermark.toString());
    n.set(LAST_KEY, M.valueToTree(lastKey));
    return new ConnectorCursor(n, Instant.now());
  }

  public static <T extends PaginationStrategy> T as(ConnectorCursor c, Class<T> type) {
    try {
      JsonNode v = c.value();
      String tag = v.get("type").asText();
      if (type == PaginationStrategy.Offset.class && tag.equals("Offset"))
        return type.cast(new PaginationStrategy.Offset(v.get("offset").asLong(), v.get(PAGE_SIZE).asInt()));
      if (type == PaginationStrategy.Keyset.class && tag.equals("Keyset"))
        return type.cast(new PaginationStrategy.Keyset(
            v.get("keyField").asText(),
            M.treeToValue(v.get(LAST_KEY), Object.class),
            v.get(PAGE_SIZE).asInt()));
      if (type == PaginationStrategy.Token.class && tag.equals("Token"))
        return type.cast(new PaginationStrategy.Token(v.get("continuationToken").asText()));
      if (type == PaginationStrategy.Timestamp.class && tag.equals("Timestamp"))
        return type.cast(new PaginationStrategy.Timestamp(Instant.parse(v.get(WATERMARK).asText())));
      if (type == PaginationStrategy.Hybrid.class && tag.equals("Hybrid"))
        return type.cast(new PaginationStrategy.Hybrid(
            Instant.parse(v.get(WATERMARK).asText()),
            M.treeToValue(v.get(LAST_KEY), Object.class)));
      throw new IllegalArgumentException("Cursor type mismatch: tag=" + tag + " expected=" + type.getSimpleName());
    } catch (Exception e) {
      throw new IllegalArgumentException("Cannot extract cursor", e);
    }
  }
}
