package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.util.Map;

@ApplicationScoped
@HookId("parse-number")
public class ParseNumberHook implements CellHook {

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState s) {
    String col = params.path("column").asText();
    Object v = row.get(col);
    if (v == null) return;
    if (v instanceof Number) return; // already numeric

    String str = v.toString().trim();
    String decimalSep = params.path("decimal-separator").asText("auto");
    boolean parensNeg = params.path("negatives-in-parens").asBoolean(false);

    boolean negative = false;
    if (parensNeg && str.startsWith("(") && str.endsWith(")")) {
      str = str.substring(1, str.length() - 1);
      negative = true;
    }

    // Normalize decimal separator
    if ("auto".equals(decimalSep)) {
      int lastDot = str.lastIndexOf('.');
      int lastComma = str.lastIndexOf(',');
      if (lastComma > lastDot) {
        // comma is the decimal separator
        str = str.replace(".", "").replace(",", ".");
      } else {
        // dot is the decimal separator (or no separator at all)
        str = str.replace(",", "");
      }
    } else if (",".equals(decimalSep)) {
      str = str.replace(".", "").replace(",", ".");
    } else {
      // dot — strip thousand separators (commas)
      str = str.replace(",", "");
    }

    // Strip currency symbols, spaces
    str = str.replaceAll("[\\s$€£¥]", "");

    try {
      BigDecimal n = new BigDecimal(str);
      if (negative) n = n.negate();
      row.put(col, n);
    } catch (NumberFormatException e) {
      s.counters().cellErrors().incrementAndGet();
      row.put(col, null);
    }
  }
}
