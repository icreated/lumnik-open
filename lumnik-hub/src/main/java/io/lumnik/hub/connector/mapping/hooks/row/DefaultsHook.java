package io.lumnik.hub.connector.mapping.hooks.row;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.ScalarParams;
import io.lumnik.hub.connector.mapping.hooks.ColumnEffect;
import io.lumnik.hub.connector.mapping.hooks.HookCatalog;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import io.lumnik.hub.connector.mapping.hooks.RowHook;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
@HookId("defaults")
public class DefaultsHook implements RowHook {
  @Override
  public void validateParams(JsonNode params) {
    params.fields().forEachRemaining(e -> ScalarParams.text("defaults hook", e.getKey(), e.getValue()));
  }

  @Override
  public List<Map<String, Object>> apply(Map<String, Object> row, JsonNode params, MappingState s) {
    Map<String, Object> out = new LinkedHashMap<>(row);
    params.fields().forEachRemaining(e -> {
      Object existing = out.get(e.getKey());
      if (existing == null || existing.toString().isBlank()) {
        out.put(e.getKey(), e.getValue().asText());
      }
    });
    return List.of(out);
  }

  @Override
  public Set<String> params() {
    return HookCatalog.KEYS_ARE_COLUMNS;
  }

  @Override
  public ColumnEffect columns(JsonNode params) {
    return ColumnEffect.addsKeys(params);
  }
}
