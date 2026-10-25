package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.ColumnEffect;
import io.lumnik.hub.connector.mapping.hooks.RegexParams;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Set;
import java.util.regex.PatternSyntaxException;

@ApplicationScoped
@HookId("regex-replace")
public class RegexReplaceHook implements CellHook {

  @Override
  public void validateParams(JsonNode params) {
    // The pattern's SHAPE and SYNTAX are knowable from the manifest — refuse a container node and a
    // bad regex once per spec, via the shared helpers. (The replacement's group references can only
    // fail against a value that MATCHES, so that check stays in apply.) An empty/absent pattern is a
    // no-op there, so skip it here too.
    String pat = RegexParams.patternText("regex-replace", "pattern", params.path("pattern"));
    if (!pat.isEmpty()) RegexParams.compileOrRefuse("regex-replace", "pattern", pat);
  }

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState s) {
    String col = params.path("column").asText();
    // Read the pattern the same way validateParams did: a bare `pattern:` (null) is absent, not the
    // literal "null" that asText() would hand back.
    String pat = RegexParams.patternText("regex-replace", "pattern", params.path("pattern"));
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

  @Override
  public Set<String> params() {
    return Set.of("column", "pattern", "replacement");
  }

  @Override
  public ColumnEffect columns(JsonNode params) {
    return ColumnEffect.readsColumn(params);
  }
}
