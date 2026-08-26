package io.lumnik.hub.connector.mapping.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;

import java.util.List;
import java.util.Map;

public interface DatasetHook {
  List<Map<String, Object>> apply(List<Map<String, Object>> rows, JsonNode params, MappingState state);
}
