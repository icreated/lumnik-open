package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.ColumnEffect;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Set;

@ApplicationScoped
@HookId("truncate")
public class TruncateHook implements CellHook {

  @Override
  public void validateParams(JsonNode params) {
    // substring(0, -1) throws StringIndexOutOfBounds, which reaches the integrator as a stack
    // trace naming neither the hook nor the knob. Refuse in the house's typed way instead — once,
    // not per row: 'length' is a param, invariant across the chunk.
    int length = params.path("length").asInt(Integer.MAX_VALUE);
    if (length < 0) {
      throw new MappingValidationException(
          "truncate hook: 'length' must be >= 0 (got " + length + ")");
    }
  }

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState s) {
    String col = params.path("column").asText();
    int length = params.path("length").asInt(Integer.MAX_VALUE);
    Object v = row.get(col);
    if (v == null) return;
    String str = v.toString();
    if (str.length() > length) row.put(col, str.substring(0, length));
  }

  @Override
  public Set<String> params() {
    return Set.of("column", "length");
  }

  @Override
  public ColumnEffect columns(JsonNode params) {
    return ColumnEffect.readsColumn(params);
  }
}
