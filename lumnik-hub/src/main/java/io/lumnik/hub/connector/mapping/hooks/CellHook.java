package io.lumnik.hub.connector.mapping.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;

import java.util.Map;

public interface CellHook {
  void apply(Map<String, Object> row, JsonNode params, MappingState state);

  /**
   * Refuse a param-SHAPE problem — one knowable from the manifest alone, invariant across every
   * row. Called ONCE per spec by {@code MappingEngine} before any row, so a hook must not repeat
   * these checks in {@code apply()}. Throw {@code MappingValidationException} on refusal; the
   * default accepts. Checks that need a row's VALUE stay in {@code apply()}.
   */
  default void validateParams(JsonNode params) {
    // no param-shape rules by default
  }
}
