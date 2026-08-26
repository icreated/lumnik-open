package io.lumnik.hub.connector.mapping.hooks.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.api.types.SkipReason;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.DatasetHook;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@HookId("skip-empty-rows")
public class SkipEmptyRowsHook implements DatasetHook {

  @Override
  public List<Map<String, Object>> apply(List<Map<String, Object>> rows, JsonNode params, MappingState state) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (var row : rows) {
      boolean allEmpty = row.values().stream().allMatch(v -> v == null || v.toString().isBlank());
      if (!allEmpty) {
        out.add(row);
      } else {
        state.dropped().add(new MappingState.DroppedRow(new HashMap<>(row),
            new SkipReason.PolicyExcluded("skip-empty-rows")));
      }
    }
    return out;
  }
}
