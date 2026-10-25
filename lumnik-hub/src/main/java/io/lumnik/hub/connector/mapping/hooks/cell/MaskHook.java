package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.ColumnEffect;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Set;

/** Masks a cell value, optionally keeping a prefix and/or suffix of clear characters. */
@ApplicationScoped
@HookId("mask")
public class MaskHook implements CellHook {

  @Override
  public void validateParams(JsonNode params) {
    // Empty and multi-character are the same defect seen from two sides. asText("*") returns ""
    // for a PRESENT-but-empty node, so the default never applies and charAt(0) threw; a longer
    // value did not throw, it was silently truncated by charAt(0), so `mask-char: "**"` masked
    // with one '*' and the manifest quietly meant something other than what it said. Refusing
    // both, once per spec, is what makes the message true of the code in apply().
    String maskCharText = params.path("mask-char").asText("*");
    if (maskCharText.length() != 1) {
      throw new MappingValidationException(
          "mask hook: 'mask-char' must be exactly one character (got "
              + (maskCharText.isEmpty() ? "an empty value" : "\"" + maskCharText + "\"") + ")");
    }
  }

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState s) {
    String col = params.path("column").asText();
    int keepLast = params.path("keep-last").asInt(0);
    int keepFirst = params.path("keep-first").asInt(0);
    char maskChar = params.path("mask-char").asText("*").charAt(0);

    Object v = row.get(col);
    if (v == null) return;
    String str = v.toString();
    // Nothing to mask if the string is too short
    if (str.length() <= keepFirst + keepLast) return;

    StringBuilder sb = new StringBuilder();
    sb.append(str, 0, keepFirst);
    for (int i = keepFirst; i < str.length() - keepLast; i++) sb.append(maskChar);
    if (keepLast > 0) sb.append(str, str.length() - keepLast, str.length());
    row.put(col, sb.toString());
  }

  @Override
  public Set<String> params() {
    return Set.of("column", "keep-first", "keep-last", "mask-char");
  }

  @Override
  public ColumnEffect columns(JsonNode params) {
    return ColumnEffect.readsColumn(params);
  }
}
