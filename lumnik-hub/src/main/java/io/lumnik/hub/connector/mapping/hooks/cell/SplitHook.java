package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Splits a single cell value into named fields (or an array).
 * Distinct from split-row which duplicates rows; this operates within a single row.
 */
@ApplicationScoped
@HookId("split")
public class SplitHook implements CellHook {

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState s) {
    String col = params.path("column").asText();
    if (col.isEmpty()) return;
    Object v = row.get(col);
    if (v == null) return;

    String by = params.path("by").asText(",");
    String[] parts = v.toString().split(Pattern.quote(by));

    if (params.has("into")) {
      JsonNode names = params.get("into");
      for (int i = 0; i < names.size() && i < parts.length; i++) {
        row.put(names.get(i).asText(), parts[i].trim());
      }
    } else if (params.path("into-array").asBoolean(false)) {
      List<String> arr = new ArrayList<>(parts.length);
      for (String p : parts) arr.add(p.trim());
      row.put(col, arr);
    }
  }
}
