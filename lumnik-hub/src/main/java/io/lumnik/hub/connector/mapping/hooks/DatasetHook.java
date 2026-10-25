package io.lumnik.hub.connector.mapping.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface DatasetHook {
  List<Map<String, Object>> apply(List<Map<String, Object>> rows, JsonNode params, MappingState state);

  /**
   * Every parameter name this hook reads, and only those (#1487): {@code HookCatalog.validate} refuses any other key,
   * so a misspelt knob is named at validate instead of dropped while the hook runs on its default. No default, so a
   * new hook cannot forget it; {@link HookCatalog#KEYS_ARE_COLUMNS} when the keys are the columns the hook fills.
   */
  Set<String> params();

  /**
   * How this hook changes a row's columns (#1500): what it reads, removes and adds, from its params. No default, so a
   * new hook cannot forget it; the validators walk a mapping with it before any row exists.
   */
  ColumnEffect columns(JsonNode params);


  /**
   * Refuse a param-SHAPE problem — one knowable from the manifest alone, invariant across every
   * chunk. Called ONCE per spec by {@code MappingEngine} before any row. Throw
   * {@code MappingValidationException} on refusal; the default accepts.
   */
  default void validateParams(JsonNode params) {
    // no param-shape rules by default
  }
}
