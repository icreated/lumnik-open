package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.ScalarParams;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.ColumnEffect;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
@HookId("parse-number")
public class ParseNumberHook implements CellHook {

  private static final List<String> SEPARATORS = List.of("auto", ".", ",");

  /**
   * {@code integer} is a boolean, or absent (a bare {@code integer:} too, the house convention): asBoolean() would
   * read "yes" as false and the manifest would quietly mean a decimal column (#1338). So is
   * {@code negatives-in-parens} (#1486).
   */
  @Override
  public void validateParams(JsonNode params) {
    ScalarParams.optionalBoolean("parse-number hook", "integer", params.path("integer"), false);
    ScalarParams.optionalBoolean("parse-number hook", "negatives-in-parens", params.path("negatives-in-parens"), false);
    // #1482: any other word — a typo, "comma" — or a list read as "" meant "dot", in silence.
    ScalarParams.optionalWord("parse-number hook", "decimal-separator", params.path("decimal-separator"), "auto",
        SEPARATORS);
  }

  /**
   * The number as written — or with {@code integer} an exact Long, a BIGINT column (#1338): {@code 7.0000} is 7;
   * {@code 7.1}, anything past BIGINT and a double that is not finite are emptied and counted, never truncated,
   * never through a double. A value already numeric is kept as it is unless {@code integer} holds it to that rule.
   */
  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState s) {
    String col = params.path("column").asText();
    Object v = row.get(col);
    if (v == null) return;
    boolean integer = params.path("integer").asBoolean(false);
    if (v instanceof Number && !integer) return; // already numeric
    try {
      BigDecimal n = v instanceof Number num ? new BigDecimal(num.toString()) : decimal(v.toString(), params);
      row.put(col, integer ? n.longValueExact() : n);
    } catch (NumberFormatException | ArithmeticException e) {
      s.counters().cellErrors().incrementAndGet();
      row.put(col, null);
    }
  }

  /** The text as a decimal: separators resolved, currency and spaces stripped, (123) negative when asked. */
  private static BigDecimal decimal(String raw, JsonNode params) {
    String str = raw.trim();
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

    BigDecimal n = new BigDecimal(str);
    return negative ? n.negate() : n;
  }

  @Override
  public Set<String> params() {
    return Set.of("column", "integer", "decimal-separator", "negatives-in-parens");
  }

  @Override
  public ColumnEffect columns(JsonNode params) {
    return ColumnEffect.readsColumn(params);
  }
}
