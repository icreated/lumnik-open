package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.text.ParsePosition;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@HookId("parse-date")
public class ParseDateHook implements CellHook {

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState s) {
    String col = params.path("column").asText();
    Object v = row.get(col);
    if (v == null) return;
    // Already a temporal type — nothing to do
    if (v instanceof LocalDate || v instanceof LocalDateTime) return;

    String str = v.toString().trim();
    List<String> formats = new ArrayList<>();
    if (params.has("formats")) {
      for (JsonNode f : params.get("formats")) formats.add(f.asText());
    }

    for (String fmt : formats) {
      boolean hasTime = fmt.contains("H") || fmt.contains("h") || fmt.contains("m") || fmt.contains("s");
      // Not LocalDate.parse: it answers "this format does not fit" — the normal case once several
      // are declared — by throwing, measured (#910) at ~7.3 µs per cell for two misses before the
      // hit, ~0.8 µs without. The Format view runs the same parse and resolve but answers null — a
      // text that does not fit costs no exception at all; one that fits but cannot resolve (day 45,
      // month 23) still throws inside the JDK, which catches it, the rare case — and the end check
      // refuses a prefix exactly as LocalDate.parse does, so the same formats fall through.
      // (Compiling each format once was measured too: ~150 ns per three-format cell, about 2% of
      // a mapped row — not worth a cache.)
      ParsePosition pos = new ParsePosition(0);
      Object parsed = DateTimeFormatter.ofPattern(fmt)
          .toFormat(hasTime ? LocalDateTime::from : LocalDate::from)
          .parseObject(str, pos);
      if (parsed != null && pos.getIndex() == str.length()) {
        row.put(col, parsed);
        return;
      }
    }

    // None matched — soft failure
    s.counters().cellErrors().incrementAndGet();
    row.put(col, null);
  }
}
