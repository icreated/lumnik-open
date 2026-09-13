package io.lumnik.hub.connector.mapping.hooks.row;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import io.lumnik.hub.connector.mapping.hooks.RowHook;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@ApplicationScoped
@HookId("split-row")
public class SplitRowHook implements RowHook {
  @Override
  public List<Map<String, Object>> apply(Map<String, Object> row, JsonNode params, MappingState s) {
    if (params.has("explode")) {
      String col = params.get("explode").asText();
      Object v = row.get(col);
      if (v instanceof List<?> list && !list.isEmpty()) {
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object item : list) {
          Map<String, Object> copy = new LinkedHashMap<>(row);
          copy.put(col, item);
          out.add(copy);
        }
        return out;
      }
      return List.of(row);
    }

    if (params.has("by")) {
      String col = params.get("by").asText();
      String sep = params.path("sep").asText(",");
      Object v = row.get(col);
      if (v == null) return List.of(row);
      String[] parts = v.toString().split(Pattern.quote(sep));
      if (parts.length == 0) return List.of(row);
      List<Map<String, Object>> out = new ArrayList<>(parts.length);
      for (String p : parts) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        copy.put(col, p.trim());
        out.add(copy);
      }
      return out;
    }

    return List.of(row);
  }
}
