package io.lumnik.hub.connector.mapping.hooks.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.ScalarParams;
import io.lumnik.hub.connector.mapping.hooks.ColumnEffect;
import io.lumnik.hub.connector.mapping.hooks.HookCatalog;
import io.lumnik.hub.connector.mapping.hooks.DatasetHook;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
@HookId("enrich-context")
public class EnrichContextHook implements DatasetHook {

  @Override
  public void validateParams(JsonNode params) {
    params.fields().forEachRemaining(e -> ScalarParams.text("enrich-context hook", e.getKey(), e.getValue()));
  }

  @Override
  public List<Map<String, Object>> apply(List<Map<String, Object>> rows, JsonNode params, MappingState state) {
    Map<String, String> ctx = new LinkedHashMap<>();
    params.fields().forEachRemaining(e -> ctx.put(e.getKey(), resolve(e.getValue().asText())));
    for (var row : rows) {
      ctx.forEach(row::putIfAbsent);
    }
    return rows;
  }

  private String resolve(String v) {
    if ("${now}".equals(v)) return Instant.now().toString();
    return v;
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
