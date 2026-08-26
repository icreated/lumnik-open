package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

/** Sets a cell to a default value when it is null or blank. Does not overwrite non-blank values. */
@ApplicationScoped
@HookId("default")
public class DefaultHook implements CellHook {

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState s) {
    String col = params.path("column").asText();
    Object existing = row.get(col);
    if (existing == null || existing.toString().isBlank()) {
      row.put(col, params.path("value").asText());
    }
  }
}
