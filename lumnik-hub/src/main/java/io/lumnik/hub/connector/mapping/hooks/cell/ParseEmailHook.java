package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.ScalarParams;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.ColumnEffect;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@ApplicationScoped
@HookId("parse-email")
public class ParseEmailHook implements CellHook {

  private static final Pattern EMAIL =
      Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

  /** {@code lower} is a boolean: "yes" or a list read as false, in silence (#1486). */
  @Override
  public void validateParams(JsonNode params) {
    ScalarParams.optionalBoolean("parse-email hook", "lower", params.path("lower"), false);
  }

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState s) {
    String col = params.path("column").asText();
    boolean lower = params.path("lower").asBoolean(false);
    Object v = row.get(col);
    if (v == null) return;

    String str = v.toString().trim();
    if (lower) str = str.toLowerCase();

    if (EMAIL.matcher(str).matches()) {
      row.put(col, str);
    } else {
      s.counters().cellErrors().incrementAndGet();
      row.put(col, null);
    }
  }

  @Override
  public Set<String> params() {
    return Set.of("column", "lower");
  }

  @Override
  public ColumnEffect columns(JsonNode params) {
    return ColumnEffect.readsColumn(params);
  }
}
