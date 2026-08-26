package io.lumnik.hub.connector.mapping.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;

import java.util.Map;

public interface CellHook {
  void apply(Map<String, Object> row, JsonNode params, MappingState state);
}
