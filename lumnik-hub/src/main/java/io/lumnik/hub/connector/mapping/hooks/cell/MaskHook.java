package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

/** Masks a cell value, optionally keeping a prefix and/or suffix of clear characters. */
@ApplicationScoped
@HookId("mask")
public class MaskHook implements CellHook {

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState s) {
    String col = params.path("column").asText();
    int keepLast = params.path("keep-last").asInt(0);
    int keepFirst = params.path("keep-first").asInt(0);
    String maskCharText = params.path("mask-char").asText("*");
    if (maskCharText.length() != 1) {
      // Empty and multi-character are the same defect seen from two sides. asText("*") returns ""
      // for a PRESENT-but-empty node, so the default never applies and charAt(0) threw; a longer
      // value did not throw, it was silently truncated by charAt(0), so `mask-char: "**"` masked
      // with one '*' and the manifest quietly meant something other than what it said. Refusing
      // both is what makes the message above true of the code below.
      throw new MappingValidationException(
          "mask hook: 'mask-char' must be exactly one character (got "
              + (maskCharText.isEmpty() ? "an empty value" : "\"" + maskCharText + "\"") + ")");
    }
    char maskChar = maskCharText.charAt(0);

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
}
