package io.lumnik.hub.connector.mapping.hooks.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.ScalarParams;
import io.lumnik.hub.connector.mapping.hooks.ColumnEffect;
import io.lumnik.hub.connector.mapping.hooks.DatasetHook;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
@HookId("require-headers")
public class RequireHeadersHook implements DatasetHook {

  /** {@code columns} and {@code any-of} are lists of header names; a scalar required nothing (#1472). */
  @Override
  public void validateParams(JsonNode params) {
    headers(params, "columns");
    headers(params, "any-of");
  }

  @Override
  public List<Map<String, Object>> apply(List<Map<String, Object>> rows, JsonNode params, MappingState state) {
    if (rows.isEmpty()) return rows;
    Set<String> present = rows.get(0).keySet();

    for (String col : headers(params, "columns")) {
      if (!present.contains(col)) {
        throw new MappingValidationException("required header missing: " + col);
      }
    }

    List<String> anyOf = headers(params, "any-of");
    if (params.has("any-of") && anyOf.stream().noneMatch(present::contains)) {
      throw new MappingValidationException("none of required headers found: " + anyOf);
    }

    return rows;
  }

  private static List<String> headers(JsonNode params, String key) {
    return params.has(key) ? ScalarParams.columnNames("require-headers hook", key, params.get(key)) : List.of();
  }

  @Override
  public Set<String> params() {
    return Set.of("columns", "any-of");
  }

  @Override
  public ColumnEffect columns(JsonNode params) {
    return ColumnEffect.builder().readsEach("columns", params.path("columns"))
        .readsAnyOf("any-of", params.path("any-of")).build();
  }
}
