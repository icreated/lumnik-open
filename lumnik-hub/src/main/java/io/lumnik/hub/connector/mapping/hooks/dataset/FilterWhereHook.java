package io.lumnik.hub.connector.mapping.hooks.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.api.types.SkipReason;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.ScalarParams;
import io.lumnik.hub.connector.mapping.hooks.ColumnEffect;
import io.lumnik.hub.connector.mapping.hooks.DatasetHook;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import io.lumnik.hub.connector.mapping.hooks.RegexParams;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@ApplicationScoped
@HookId("filter-where")
public class FilterWhereHook implements DatasetHook {

  /**
   * The regex and the values compared with are the manifest's, so they are refused once per spec — and at apply
   * (#1403) — not by the first chunk.
   */
  @Override
  public void validateParams(JsonNode params) {
    regexOf(params);
    equalsOf(params);
    parseIn(params);
  }

  @Override
  public List<Map<String, Object>> apply(List<Map<String, Object>> rows, JsonNode params, MappingState state) {
    String column = params.path("column").asText();
    if (column.isEmpty()) return rows;

    String equals = equalsOf(params);
    Pattern regex = regexOf(params);
    Set<String> in = parseIn(params);

    List<Map<String, Object>> out = new ArrayList<>();
    for (var row : rows) {
      if (keep(row.get(column), equals, regex, in)) {
        out.add(row);
      } else {
        state.dropped().add(new MappingState.DroppedRow(new HashMap<>(row),
            new SkipReason.PolicyExcluded("filter-where")));
      }
    }
    return out;
  }

  /**
   * A bad regex is a typed refusal naming the hook and the 'regex' knob, not a raw PatternSyntaxException stack
   * trace; a container-node regex is refused too, not flattened to "" and silently ignored. Null when absent.
   */
  private static Pattern regexOf(JsonNode params) {
    String regexStr = RegexParams.patternText("filter-where", "regex", params.path("regex"));
    return regexStr.isEmpty() ? null : RegexParams.compileOrRefuse("filter-where", "regex", regexStr);
  }

  /** {@code equals} as text, or a typed refusal (#1465). Null when absent. */
  private static String equalsOf(JsonNode params) {
    return params.has("equals") ? ScalarParams.text("filter-where hook", "equals", params.get("equals")) : null;
  }

  /** {@code in} as a set of texts, or a typed refusal (#1465). Empty when absent. */
  private static Set<String> parseIn(JsonNode params) {
    return params.has("in") ? Set.copyOf(ScalarParams.textList("filter-where hook", "in", params.get("in"))) : Set.of();
  }

  /** Keep the value when it satisfies every configured criterion (equals AND regex AND in). */
  private static boolean keep(Object value, String equals, Pattern regex, Set<String> in) {
    String s = value == null ? "" : value.toString();
    if (equals != null && !s.equals(equals)) return false;
    if (regex != null && !regex.matcher(s).matches()) return false;
    return in.isEmpty() || in.contains(s);
  }

  @Override
  public Set<String> params() {
    return Set.of("column", "equals", "regex", "in");
  }

  @Override
  public ColumnEffect columns(JsonNode params) {
    return ColumnEffect.readsColumn(params);
  }
}
