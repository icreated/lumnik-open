package io.lumnik.hub.connector.mapping.hooks.row;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.api.types.SkipReason;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import io.lumnik.hub.connector.mapping.hooks.RowHook;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@HookId("validate-row")
public class ValidateRowHook implements RowHook {
  @Override
  public List<Map<String, Object>> apply(Map<String, Object> row, JsonNode params, MappingState s) {
    if (params.has("required")) {
      for (JsonNode req : params.get("required")) {
        Object v = row.get(req.asText());
        if (v == null || v.toString().isBlank()) {
          s.dropped().add(new MappingState.DroppedRow(new HashMap<>(row),
              new SkipReason.ValidationFailed(
                  List.of("required column '" + req.asText() + "' is missing or blank"))));
          return List.of();
        }
      }
    }
    return List.of(row);
  }
}
