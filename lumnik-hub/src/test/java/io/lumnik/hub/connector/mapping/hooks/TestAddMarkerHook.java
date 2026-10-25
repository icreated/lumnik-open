package io.lumnik.hub.connector.mapping.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Test-only DatasetHook used by MappingEngineIntegrationIT to verify that CDI discovery,
 * HookCatalog registration, and MappingEngine dispatch all work end-to-end.
 */
@ApplicationScoped
@HookId("test-add-marker")
public class TestAddMarkerHook implements DatasetHook {

  @Override
  public List<Map<String, Object>> apply(List<Map<String, Object>> rows, JsonNode params, MappingState state) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> r : rows) {
      Map<String, Object> copy = new HashMap<>(r);
      copy.put("marker", "added-by-test-hook");
      out.add(copy);
    }
    return out;
  }

  @Override
  public Set<String> params() {
    return Set.of();
  }

  @Override
  public ColumnEffect columns(JsonNode params) {
    return ColumnEffect.builder().adds("marker").build();
  }
}
