package io.lumnik.hub.connector.mapping.hooks.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.api.types.SkipReason;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.DatasetHook;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import io.lumnik.hub.connector.mapping.hooks.RegexParams;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@ApplicationScoped
@HookId("filter-where")
public class FilterWhereHook implements DatasetHook {

  @Override
  public List<Map<String, Object>> apply(List<Map<String, Object>> rows, JsonNode params, MappingState state) {
    String column = params.path("column").asText();
    if (column.isEmpty()) return rows;

    String equals = params.has("equals") ? params.get("equals").asText() : null;
    // A bad regex is a typed refusal naming the hook and the 'regex' knob, not a raw
    // PatternSyntaxException stack trace; a container-node regex is refused too, not flattened to ""
    // and silently ignored. filter-where is one-shot per chunk, so this fires even on an empty chunk.
    String regexStr = RegexParams.patternText("filter-where", "regex", params.path("regex"));
    Pattern regex = regexStr.isEmpty() ? null : RegexParams.compileOrRefuse("filter-where", "regex", regexStr);
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

  private static Set<String> parseIn(JsonNode params) {
    Set<String> in = new HashSet<>();
    if (params.has("in")) {
      for (JsonNode v : params.get("in")) in.add(v.asText());
    }
    return in;
  }

  /** Keep the value when it satisfies every configured criterion (equals AND regex AND in). */
  private static boolean keep(Object value, String equals, Pattern regex, Set<String> in) {
    String s = value == null ? "" : value.toString();
    if (equals != null && !s.equals(equals)) return false;
    if (regex != null && !regex.matcher(s).matches()) return false;
    return in.isEmpty() || in.contains(s);
  }
}
