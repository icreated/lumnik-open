package io.lumnik.hub.connector.mapping.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;

import java.util.List;
import java.util.Map;

public interface RowHook {
  List<Map<String, Object>> apply(Map<String, Object> row, JsonNode params, MappingState state);

  default List<Map<String, Object>> flush(JsonNode params, MappingState state) {
    return List.of();
  }
}
