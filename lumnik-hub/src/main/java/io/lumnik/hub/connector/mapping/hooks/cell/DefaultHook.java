package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.ScalarParams;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.ColumnEffect;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Set;

/** Sets a cell to a default value when it is null or blank. Does not overwrite non-blank values. */
@ApplicationScoped
@HookId("default")
public class DefaultHook implements CellHook {

  @Override
  public void validateParams(JsonNode params) {
    if (params.has("value")) ScalarParams.text("default hook", "value", params.get("value"));
  }

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState s) {
    String col = params.path("column").asText();
    Object existing = row.get(col);
    if (existing == null || existing.toString().isBlank()) {
      row.put(col, params.path("value").asText());
    }
  }

  @Override
  public Set<String> params() {
    return Set.of("column", "value");
  }

  @Override
  public ColumnEffect columns(JsonNode params) {
    return ColumnEffect.builder().adds(params.path("column")).build();   // fills the column, absent included
  }
}
