package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.regex.PatternSyntaxException;

@ApplicationScoped
@HookId("regex-replace")
public class RegexReplaceHook implements CellHook {

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState s) {
    String col = params.path("column").asText();
    String pat = params.path("pattern").asText();
    String repl = params.path("replacement").asText("");
    Object v = row.get(col);
    if (v == null || pat.isEmpty()) return;
    try {
      row.put(col, v.toString().replaceAll(pat, repl));
    } catch (PatternSyntaxException e) {
      throw new MappingValidationException(
          "regex-replace hook: 'pattern' is not a valid regex: " + e.getDescription(), e);
    } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
      // A '$' or a '\\' in the replacement is a group reference, not a literal: a lone '$' or a
      // reference to a group the pattern does not have blows up mid-run.
      throw new MappingValidationException(
          "regex-replace hook: 'replacement' is not usable with this pattern "
              + "(a lone '$' or '\\\\' is a group reference — escape it): " + e.getMessage(), e);
    }
  }
}
