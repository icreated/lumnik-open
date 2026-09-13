package io.lumnik.hub.connector.mapping.hooks.row;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import io.lumnik.hub.connector.mapping.hooks.RowHook;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@HookId("coalesce")
public class CoalesceHook implements RowHook {
  @Override
  public List<Map<String, Object>> apply(Map<String, Object> row, JsonNode params, MappingState s) {
    String target = params.path("column").asText();
    Map<String, Object> out = new LinkedHashMap<>(row);

    Object existing = out.get(target);
    if (existing != null && !existing.toString().isBlank()) {
      return List.of(out);
    }

    if (params.has("from")) {
      for (JsonNode src : params.get("from")) {
        Object v = row.get(src.asText());
        if (v != null && !v.toString().isBlank()) {
          out.put(target, v);
          break;
        }
      }
    }
    return List.of(out);
  }
}
