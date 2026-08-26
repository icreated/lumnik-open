package io.lumnik.hub.connector.mapping.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;

/**
 * Test-only RowHook that emits nothing for every row WITHOUT recording a drop in
 * {@link MappingState#dropped()} — the exact shape of a future hook bug the
 * {@code MappingEngine.runRowChain} defensive net must catch. Used by
 * {@code MappingDropAccountingTest} to prove the net fires.
 */
@ApplicationScoped
@HookId("test-silent-drop")
public class TestSilentDropRowHook implements RowHook {

  @Override
  public List<Map<String, Object>> apply(Map<String, Object> row, JsonNode params, MappingState state) {
    return List.of();
  }
}
