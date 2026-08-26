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
@HookId("defaults")
public class DefaultsHook implements RowHook {
  @Override
  public List<Map<String, Object>> apply(Map<String, Object> row, JsonNode params, MappingState s) {
    Map<String, Object> out = new HashMap<>(row);
    params.fields().forEachRemaining(e -> {
      Object existing = out.get(e.getKey());
      if (existing == null || existing.toString().isBlank()) {
        out.put(e.getKey(), e.getValue().asText());
      }
    });
    return List.of(out);
  }
}
