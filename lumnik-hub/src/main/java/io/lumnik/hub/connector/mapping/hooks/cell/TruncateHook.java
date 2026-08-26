package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

@ApplicationScoped
@HookId("truncate")
public class TruncateHook implements CellHook {

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState s) {
    String col = params.path("column").asText();
    int length = params.path("length").asInt(Integer.MAX_VALUE);
    Object v = row.get(col);
    if (v == null) return;
    String str = v.toString();
    if (str.length() > length) row.put(col, str.substring(0, length));
  }
}
