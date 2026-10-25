package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.ScalarParams;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.ColumnEffect;
import io.lumnik.hub.connector.mapping.hooks.HookCatalog;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Set;

/**
 * Carries a value onto the rows that follow (#1452): a non-empty cell of {@code columns} becomes the value carried, an
 * empty one (null or blank) receives it. {@code reset-when: { column, in }} forgets what is carried, before the row's
 * own value is captured — a {@code LIN} carries its own EAN, never the previous one's. Bounded by the file: the engine
 * starts each file empty and keeps the state across chunks and resumes ({@link #carriesState()}).
 */
@ApplicationScoped
@HookId("fill-down")
public class FillDownHook implements CellHook {

  private static final String OWNER = "fill-down hook";
  private static final Set<String> RESET_KEYS = Set.of("column", "in");

  @Override
  public void validateParams(JsonNode params) {
    ScalarParams.columnNames(OWNER, "columns", params.path("columns"));
    JsonNode reset = params.path("reset-when");
    if (reset.isMissingNode() || reset.isNull()) return;
    if (!reset.isObject()) {
      throw new MappingValidationException(OWNER + ": 'reset-when' must be a block of column and in (got "
          + MappingValidationException.typeName(reset) + ")");
    }
    HookCatalog.refuseUnknownKeys(OWNER, "reset-when.", reset, RESET_KEYS);
    ScalarParams.columnName(OWNER, "reset-when.column", reset.path("column"));
    ScalarParams.textList(OWNER, "reset-when.in", reset.path("in"));
  }

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState state) {
    Map<String, Object> carried = state.carry();
    JsonNode reset = params.path("reset-when");
    if (reset.isObject() && marks(present(row, reset.path("column").asText()), reset.path("in"))) {
      carried.clear();
    }
    for (JsonNode c : params.path("columns")) {
      String column = c.asText();
      Object value = present(row, column);
      if (value == null || value.toString().isBlank()) {
        if (carried.containsKey(column)) row.put(column, carried.get(column));
      } else {
        carried.put(column, value);
      }
    }
  }

  /** The row's value of a column it must hold: a column nothing before the hook produced is refused by name. */
  private static Object present(Map<String, Object> row, String column) {
    if (!row.containsKey(column)) {
      throw new MappingValidationException(OWNER + ": column '" + column + "' is not in the row — no source column "
          + "or earlier hook produces it");
    }
    return row.get(column);
  }

  private static boolean marks(Object value, JsonNode in) {
    if (value == null) return false;
    String text = value.toString();
    for (JsonNode marker : in) {
      if (marker.asText().equals(text)) return true;
    }
    return false;
  }

  @Override
  public Set<String> params() {
    return Set.of("columns", "reset-when");
  }

  @Override
  public ColumnEffect columns(JsonNode params) {
    return ColumnEffect.builder().readsEach("columns", params.path("columns"))
        .reads("reset-when.column", params.path("reset-when").path("column")).build();
  }


  @Override
  public boolean carriesState() {
    return true;
  }
}
