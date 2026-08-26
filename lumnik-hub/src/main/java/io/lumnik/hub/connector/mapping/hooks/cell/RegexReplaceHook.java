package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

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
    row.put(col, v.toString().replaceAll(pat, repl));
  }
}
