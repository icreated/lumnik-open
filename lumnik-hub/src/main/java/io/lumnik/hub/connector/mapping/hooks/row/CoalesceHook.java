package io.lumnik.hub.connector.mapping.hooks.row;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.ScalarParams;
import io.lumnik.hub.connector.mapping.hooks.ColumnEffect;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import io.lumnik.hub.connector.mapping.hooks.RowHook;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
@HookId("coalesce")
public class CoalesceHook implements RowHook {

  /** {@code from} is a list of column names; a scalar coalesced nothing, a list element read "" (#1472). */
  @Override
  public void validateParams(JsonNode params) {
    if (params.has("from")) ScalarParams.columnNames("coalesce hook", "from", params.get("from"));
  }

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

  @Override
  public Set<String> params() {
    return Set.of("column", "from");
  }

  @Override
  public ColumnEffect columns(JsonNode params) {
    return ColumnEffect.builder().readsAnyOf("from", params.path("from")).adds(params.path("column")).build();
  }
}
