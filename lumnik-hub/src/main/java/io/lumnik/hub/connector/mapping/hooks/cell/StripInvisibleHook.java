package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.ColumnEffect;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Set;

/**
 * Removes invisible/zero-width Unicode characters that silently corrupt string comparisons.
 * Covers: ZWSP (U+200B), ZWNJ (U+200C), ZWJ (U+200D), BOM (U+FEFF),
 * soft hyphen (U+00AD), word joiner (U+2060), and all Unicode Cf format characters.
 */
@ApplicationScoped
@HookId("strip-invisible")
public class StripInvisibleHook implements CellHook {

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState s) {
    for (String col : CleanHook.columnList(params, row)) {
      Object v = row.get(col);
      if (!(v instanceof String str)) continue;
      row.put(col, transform(str));
    }
  }

  private String transform(String input) {
    // \p{Cf} covers all Unicode Format characters (ZWSP, ZWJ, ZWNJ, BOM, word joiner…)
    // U+00AD (soft hyphen) is also Cf in Unicode but explicit for clarity.
    return input.replaceAll("[\\p{Cf}\\u00AD]", "");
  }

  @Override
  public Set<String> params() {
    return Set.of("columns");
  }

  @Override
  public ColumnEffect columns(JsonNode params) {
    return ColumnEffect.readsColumns(params);
  }
}
