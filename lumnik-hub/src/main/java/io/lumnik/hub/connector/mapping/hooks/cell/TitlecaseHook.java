package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

@ApplicationScoped
@HookId("titlecase")
public class TitlecaseHook implements CellHook {

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState s) {
    for (String col : CleanHook.columnList(params, row)) {
      Object v = row.get(col);
      if (v == null) continue;
      String[] words = v.toString().split("\\s+");
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < words.length; i++) {
        if (i > 0) sb.append(' ');
        String w = words[i];
        if (!w.isEmpty()) {
          sb.append(Character.toUpperCase(w.charAt(0)));
          if (w.length() > 1) sb.append(w.substring(1).toLowerCase());
        }
      }
      row.put(col, sb.toString());
    }
  }
}
