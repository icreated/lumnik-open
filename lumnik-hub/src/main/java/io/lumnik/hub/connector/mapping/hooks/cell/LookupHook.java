package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

@ApplicationScoped
@HookId("lookup")
public class LookupHook implements CellHook {

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState s) {
    String col = params.path("column").asText();
    Object v = row.get(col);
    if (v == null) return;

    JsonNode table = params.path("table");
    String key = v.toString();

    if (table.has(key)) {
      row.put(col, table.get(key).asText());
      return;
    }

    // Key not found in table
    String onMissing = params.path("on-missing").asText("keep");
    switch (onMissing) {
      case "null" -> {
        s.counters().cellErrors().incrementAndGet();
        row.put(col, null);
      }
      case "keep" -> { /* leave original value as-is */ }
      case "error" -> throw new MappingValidationException(
          "lookup: no match for '" + key + "' in column '" + col + "'");
      default -> row.put(col, onMissing); // literal fallback value
    }
  }
}
