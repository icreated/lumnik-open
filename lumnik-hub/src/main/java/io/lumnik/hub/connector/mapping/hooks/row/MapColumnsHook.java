package io.lumnik.hub.connector.mapping.hooks.row;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import io.lumnik.hub.connector.mapping.hooks.RowHook;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@HookId("map-columns")
public class MapColumnsHook implements RowHook {
  @Override
  public List<Map<String, Object>> apply(Map<String, Object> row, JsonNode params, MappingState s) {
    Map<String, Object> out = new HashMap<>(row);
    if (params.has("rename")) {
      params.get("rename").fields().forEachRemaining(e -> {
        Object v = out.remove(e.getKey());
        if (v != null) out.put(e.getValue().asText(), v);
      });
    }
    if (params.has("drop")) {
      for (JsonNode col : params.get("drop")) {
        out.remove(col.asText());
      }
    }
    return List.of(out);
  }
}
