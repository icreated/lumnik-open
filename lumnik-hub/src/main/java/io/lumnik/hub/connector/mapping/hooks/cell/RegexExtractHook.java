package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@ApplicationScoped
@HookId("regex-extract")
public class RegexExtractHook implements CellHook {

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState s) {
    String col = params.path("column").asText();
    String patternStr = params.path("pattern").asText();
    Object v = row.get(col);
    if (v == null || patternStr.isEmpty()) return;

    Pattern pattern;
    try {
      pattern = Pattern.compile(patternStr);
    } catch (PatternSyntaxException e) {
      throw new MappingValidationException(
          "regex-extract hook: 'pattern' is not a valid regex: " + e.getDescription(), e);
    }
    Matcher m = pattern.matcher(v.toString());
    // Try full match first, then find
    boolean found = m.matches();
    if (!found) {
      m.reset();
      found = m.find();
    }
    if (!found) return;

    if (params.has("groups")) {
      params.get("groups").fields().forEachRemaining(e -> {
        try {
          int idx = Integer.parseInt(e.getKey());
          if (idx <= m.groupCount()) {
            row.put(e.getValue().asText(), m.group(idx));
          }
        } catch (NumberFormatException | IllegalStateException ex) {
          // ignore bad group index
        }
      });
    }
  }
}
