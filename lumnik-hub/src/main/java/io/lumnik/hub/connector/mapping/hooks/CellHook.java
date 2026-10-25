package io.lumnik.hub.connector.mapping.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;

import java.util.Map;
import java.util.Set;

public interface CellHook {
  void apply(Map<String, Object> row, JsonNode params, MappingState state);

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
   * Whether this hook carries values from one row to the following ones (#1452). The engine then keeps its state across
   * chunks and resumes, and the local reader hands it one file per chunk. Default: a cell hook sees one row.
   */
  default boolean carriesState() {
    return false;
  }

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
