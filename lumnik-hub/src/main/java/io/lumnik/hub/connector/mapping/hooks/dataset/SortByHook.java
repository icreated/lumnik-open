package io.lumnik.hub.connector.mapping.hooks.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.ScalarParams;
import io.lumnik.hub.connector.mapping.hooks.ColumnEffect;
import io.lumnik.hub.connector.mapping.hooks.DatasetHook;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
@HookId("sort-by")
public class SortByHook implements DatasetHook {

  /** {@code columns} is a list of column names; a scalar sorted by nothing, a list element by "" (#1472). */
  @Override
  public void validateParams(JsonNode params) {
    parseColumns(params);
  }

  @Override
  public List<Map<String, Object>> apply(List<Map<String, Object>> rows, JsonNode params, MappingState state) {
    int maxRows = params.path("max-rows").asInt(100_000);
    if (rows.size() > maxRows) {
      throw new MappingValidationException(
          "sort-by max-rows exceeded: " + rows.size() + " > " + maxRows
              + " (input must be pre-sorted or increase max-rows)");
    }
    List<String> columns = parseColumns(params);
    if (columns.isEmpty()) return rows;

    List<Map<String, Object>> sorted = new ArrayList<>(rows);
    sorted.sort(byColumns(columns));
    return sorted;
  }

  private static List<String> parseColumns(JsonNode params) {
    return params.has("columns") ? ScalarParams.columnNames("sort-by hook", "columns", params.get("columns")) : List.of();
  }

  /** Lexicographic comparator over the given columns; null cells sort as empty strings. */
  private static Comparator<Map<String, Object>> byColumns(List<String> columns) {
    return (a, b) -> {
      for (String col : columns) {
        int c = asText(a.get(col)).compareTo(asText(b.get(col)));
        if (c != 0) return c;
      }
      return 0;
    };
  }

  private static String asText(Object v) {
    return v == null ? "" : v.toString();
  }

  @Override
  public Set<String> params() {
    return Set.of("columns", "max-rows");
  }

  @Override
  public ColumnEffect columns(JsonNode params) {
    return ColumnEffect.builder().readsEach("columns", params.path("columns")).build();
  }
}
