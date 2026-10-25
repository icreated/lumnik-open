package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.ScalarParams;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.ColumnEffect;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Set;

@ApplicationScoped
@HookId("lookup")
public class LookupHook implements CellHook {

  @Override
  public void validateParams(JsonNode params) {
    // A present-but-non-object `table` makes every lookup miss in silence — table.has(key) is false
    // on a scalar/array, so a mis-typed table quietly changes nothing. A bare `table:` (null) or an
    // absent one is "no table" (all keep), the house convention; only a present non-object is refused.
    JsonNode table = params.path("table");
    if (!table.isMissingNode() && !table.isNull() && !table.isObject()) {
      throw new MappingValidationException(
          "lookup hook: 'table' must be a map of value to replacement (got "
          + MappingValidationException.typeName(table) + ")");
    }
    // A replacement is written into the cell (#1441): a list or a map would land as "", a null as "null".
    table.fields().forEachRemaining(e -> ScalarParams.text("lookup hook", "table." + e.getKey(), e.getValue()));
    // A list or a map on-missing would read as the literal "" (#1463); a null is the `null` policy, read below.
    JsonNode onMissing = params.path("on-missing");
    if (!onMissing.isMissingNode() && !onMissing.isNull()) ScalarParams.text("lookup hook", "on-missing", onMissing);
  }

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState s) {
    String col = params.path("column").asText();
    Object v = row.get(col);
    if (v == null) return;

    JsonNode table = params.path("table");
    String key = v.toString();

    if (table.has(key)) {
      row.put(col, table.get(key).asText());
      return;
    }

    // Key not found in table. A YAML null (unquoted or bare `on-missing:`) is the null policy, not absent (#1463).
    JsonNode onMissingNode = params.path("on-missing");
    String onMissing = onMissingNode.isNull() ? "null" : onMissingNode.asText("keep");
    switch (onMissing) {
      case "null" -> {
        s.counters().cellErrors().incrementAndGet();
        row.put(col, null);
      }
      case "keep" -> { /* leave original value as-is */ }
      // The column, never the cell: the message reaches the run, and errors speak structure (#1467).
      case "error" -> throw new MappingValidationException(
          "lookup: no match in column '" + col + "' (on-missing: error)");
      default -> row.put(col, onMissing); // literal fallback value
    }
  }

  @Override
  public Set<String> params() {
    return Set.of("column", "table", "on-missing");
  }

  @Override
  public ColumnEffect columns(JsonNode params) {
    return ColumnEffect.readsColumn(params);
  }
}
