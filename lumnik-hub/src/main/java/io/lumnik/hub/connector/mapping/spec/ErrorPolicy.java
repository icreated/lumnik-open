package io.lumnik.hub.connector.mapping.spec;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingValidationException;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A mapping's {@code on-error}: what a value a cell hook could not deliver does to its row (#1408) — {@code soft}
 * writes the row with the cell as the hook left it, {@code dlq} dead-letters the row as it was read, {@code fail}
 * stops the run. {@code default} covers every column {@code fields} does not name.
 */
public record ErrorPolicy(
    Mode defaultMode,
    Map<String, Mode> fields  // override per column
) {
  public enum Mode { SOFT, DLQ, FAIL }
  public static ErrorPolicy soft() { return new ErrorPolicy(Mode.SOFT, Map.of()); }

  public Mode modeFor(String column) { return fields.getOrDefault(column, defaultMode); }

  /** Whether some column dead-letters its row — a drop the mapping must be able to count once per source row. */
  public boolean deadLetters() {
    return defaultMode == Mode.DLQ || fields.containsValue(Mode.DLQ);
  }

  public boolean isSoft() {
    return defaultMode == Mode.SOFT && fields.values().stream().allMatch(Mode.SOFT::equals);
  }

  /** The {@code on-error} block, absent meaning {@link #soft()}; a shape or mode it cannot mean is refused. */
  public static ErrorPolicy parse(JsonNode n) {
    if (n.isMissingNode() || n.isNull()) return soft();
    if (!n.isObject()) throw new MappingValidationException("on-error must be a map of default and fields");
    var fields = new HashMap<String, Mode>();
    JsonNode fieldsNode = n.path("fields");
    if (!fieldsNode.isMissingNode() && !fieldsNode.isObject()) {
      throw new MappingValidationException("on-error.fields must map a column to a mode");
    }
    fieldsNode.fields().forEachRemaining(e -> fields.put(e.getKey(), mode(e.getValue())));
    JsonNode def = n.path("default");
    return new ErrorPolicy(def.isMissingNode() ? Mode.SOFT : mode(def), fields);
  }

  private static Mode mode(JsonNode v) {
    String s = v.isTextual() ? v.asText().toUpperCase(Locale.ROOT) : "";
    for (Mode m : Mode.values()) {
      if (m.name().equals(s)) return m;
    }
    throw new MappingValidationException("on-error mode must be soft, dlq or fail");
  }
}
