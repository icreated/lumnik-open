package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@HookId("clean")
public class CleanHook implements CellHook {

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState s) {
    List<String> columns = columnList(params, row);
    boolean strip = params.path("strip").asBoolean(false);
    boolean removeCtrl = params.path("remove-control-chars").asBoolean(false);
    String form = params.has("normalize-unicode") ? params.get("normalize-unicode").asText() : null;

    for (String col : columns) {
      Object v = row.get(col);
      if (v == null || !(v instanceof String str2)) continue;
      String str = str2;
      // Strip BOM (U+FEFF)
      if (!str.isEmpty() && str.charAt(0) == '\uFEFF') str = str.substring(1);
      if (strip) str = str.strip();
      if (removeCtrl) str = str.replaceAll("\\p{Cntrl}", "");
      if (form != null) str = Normalizer.normalize(str, Normalizer.Form.valueOf(form));
      row.put(col, str);
    }
  }

  /** Returns explicit column list, or all row keys when columns is ["*"] or absent. */
  static List<String> columnList(JsonNode params, Map<String, Object> row) {
    if (!params.has("columns")) return new ArrayList<>(row.keySet());
    JsonNode cols = params.get("columns");
    if (cols.isArray() && cols.size() == 1 && "*".equals(cols.get(0).asText())) {
      return new ArrayList<>(row.keySet());
    }
    List<String> out = new ArrayList<>();
    for (JsonNode c : cols) out.add(c.asText());
    return out;
  }
}
