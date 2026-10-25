package io.lumnik.hub.connector.mapping.hooks.row;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.ScalarParams;
import io.lumnik.hub.connector.mapping.hooks.ColumnEffect;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import io.lumnik.hub.connector.mapping.hooks.RowHook;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One row per part of a cell ({@code by} + {@code sep}) or per item of a list column ({@code explode}).
 *
 * <p>#1421: a dynamic table is content-addressed ({@code UNIQUE (tenant_id, _row_hash)}), so two identical parts are
 * ONE row — an order of two identical T-shirts landed as one, counted nowhere. {@code position: <column>} writes each
 * fragment's 1-based place in the cell, which tells them apart. It is on every row, {@code null} when the cell is, so
 * the table's shape never depends on which row came first.
 */
@ApplicationScoped
@HookId("split-row")
public class SplitRowHook implements RowHook {

  @Override
  public void validateParams(JsonNode params) {
    // #1472: the split column and `sep` are text; a list or a map read as "", a null as the column "null", and
    // blank text names no column.
    if (params.has("explode")) ScalarParams.columnName("split-row hook", "explode", params.get("explode"));
    if (params.has("by")) ScalarParams.columnName("split-row hook", "by", params.get("by"));
    // #1485: an empty `sep` cut the value into its characters (and, beside `escape`, never advanced).
    String sep = ScalarParams.optionalSeparator("split-row hook", "sep", params.path("sep"), ",");
    JsonNode escape = params.path("escape");
    boolean escaped = !escape.isMissingNode() && !escape.isNull();
    if (escaped && !params.has("by")) {
      throw new MappingValidationException("split-row hook: 'escape' goes with 'by' — 'explode' splits no text");
    }
    if (escaped && (!escape.isTextual() || escape.asText().length() != 1 || sep.contains(escape.asText()))) {
      throw new MappingValidationException(
          "split-row hook: 'escape' must be one character, not part of 'sep' (EDIFACT's release character is \"?\")");
    }
    JsonNode position = params.path("position");
    if (position.isMissingNode() || position.isNull()) return;
    String split = params.has("explode") ? params.path("explode").asText() : params.path("by").asText();
    if (!position.isTextual() || position.asText().isBlank() || position.asText().equals(split)) {
      String got = position.isTextual() ? "'" + position.asText() + "'" : MappingValidationException.typeName(position);
      throw new MappingValidationException(
          "split-row hook: 'position' must name a column other than the one it splits (got " + got + ")");
    }
  }

  @Override
  public List<Map<String, Object>> apply(Map<String, Object> row, JsonNode params, MappingState s) {
    String position = params.path("position").isTextual() ? params.path("position").asText() : null;

    if (params.has("explode")) {
      String col = params.get("explode").asText();
      Object v = row.get(col);
      if (v instanceof List<?> list && !list.isEmpty()) {
        return fragments(row, col, list, position);
      }
      return List.of(unsplit(row, v, position));
    }

    if (params.has("by")) {
      String col = params.get("by").asText();
      String sep = params.path("sep").asText(",");
      Object v = row.get(col);
      if (v == null) return List.of(unsplit(row, null, position));
      JsonNode escape = params.path("escape");
      List<String> parts = escape.isTextual()
          ? splitEscaped(v.toString(), sep, escape.asText().charAt(0))
          : Arrays.asList(v.toString().split(Pattern.quote(sep)));
      if (parts.isEmpty()) return List.of(unsplit(row, null, position));   // "|" alone: no item
      return fragments(row, col, parts.stream().map(String::trim).toList(), position);
    }

    return List.of(row);
  }

  /**
   * #1451: {@code sep} cuts unless the escape character stands before it; the escape character is removed and the
   * character after it kept as written ({@code ?'} → {@code '}, {@code ??} → {@code ?}). Trailing empty parts are
   * dropped, as {@code String.split} drops them.
   */
  static List<String> splitEscaped(String text, String sep, char escape) {
    if (text.isEmpty()) return List.of("");   // as "".split(...) gives
    if (sep.isEmpty()) return List.of(text);   // never cut on nothing: the scan would not advance
    List<String> parts = new ArrayList<>();
    StringBuilder part = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == escape && text.startsWith(sep, i + 1)) {
        part.append(sep);                     // the whole separator, escaped
        i += sep.length();
      } else if (c == escape && i + 1 < text.length()) {
        part.append(text.charAt(++i));
      } else if (text.startsWith(sep, i)) {
        parts.add(part.toString());
        part.setLength(0);
        i += sep.length() - 1;
      } else {
        part.append(c);
      }
    }
    parts.add(part.toString());
    while (!parts.isEmpty() && parts.getLast().isEmpty()) parts.removeLast();
    return parts;
  }

  private static List<Map<String, Object>> fragments(Map<String, Object> row, String col, List<?> items,
                                                     String position) {
    List<Map<String, Object>> out = new ArrayList<>(items.size());
    for (int i = 0; i < items.size(); i++) {
      Map<String, Object> copy = new LinkedHashMap<>(row);
      copy.put(col, items.get(i));
      if (position != null) copy.put(position, i + 1);
      out.add(copy);
    }
    return out;
  }

  /** The row as it came, with its position: 1 for the one value it holds, null for none. */
  private static Map<String, Object> unsplit(Map<String, Object> row, Object v, String position) {
    if (position == null) return row;
    Map<String, Object> copy = new LinkedHashMap<>(row);
    copy.put(position, v == null || v instanceof List<?> ? null : 1);
    return copy;
  }

  @Override
  public Set<String> params() {
    return Set.of("explode", "by", "sep", "escape", "position");
  }

  @Override
  public ColumnEffect columns(JsonNode params) {
    return ColumnEffect.builder().reads("explode", params.path("explode")).reads("by", params.path("by"))
        .adds(params.path("position")).build();
  }
}
