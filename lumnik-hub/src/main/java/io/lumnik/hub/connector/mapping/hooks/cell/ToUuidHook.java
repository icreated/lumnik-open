package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.UUID;

/** Validates (and optionally generates) a UUID cell value. */
@ApplicationScoped
@HookId("to-uuid")
public class ToUuidHook implements CellHook {

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState s) {
    String col = params.path("column").asText();
    boolean generate = params.path("generate").asBoolean(false);

    Object v = row.get(col);
    if (v == null) {
      if (generate) row.put(col, UUID.randomUUID().toString());
      return;
    }

    try {
      UUID.fromString(v.toString()); // valid — leave as-is
    } catch (IllegalArgumentException e) {
      row.put(col, generate ? UUID.randomUUID().toString() : null);
    }
  }
}
