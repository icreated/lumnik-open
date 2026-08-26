package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.text.Normalizer;
import java.util.Map;

/**
 * Strips combining diacritical marks for systems requiring ASCII-only identifiers.
 * Example: "Café" → "Cafe", "ñoño" → "nono".
 */
@ApplicationScoped
@HookId("remove-diacritics")
public class RemoveDiacriticsHook implements CellHook {

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState s) {
    for (String col : CleanHook.columnList(params, row)) {
      Object v = row.get(col);
      if (!(v instanceof String str)) continue;
      row.put(col, transform(str));
    }
  }

  private String transform(String input) {
    String nfd = Normalizer.normalize(input, Normalizer.Form.NFD);
    return nfd.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
  }
}
