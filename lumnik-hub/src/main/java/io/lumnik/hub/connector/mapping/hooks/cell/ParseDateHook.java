package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.text.ParsePosition;
import java.time.LocalDate;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Parses a text cell with the first declared format that fits: a date, or a date and time. A time
 * carries no offset in the source, so {@code zone} says where it was written: given, the value
 * becomes that instant ({@code 23:30} in {@code Europe/Paris} is {@code 21:30Z} in summer); absent,
 * the value stays a wall-clock time and the writer stores it as UTC (#1092) — never the zone of the
 * machine the hub runs on. In a daylight-saving gap or overlap, java.time's rule applies (a time in
 * the gap moves forward by the gap, an overlap takes the earlier offset).
 */
@ApplicationScoped
@HookId("parse-date")
public class ParseDateHook implements CellHook {

  @Override
  public void validateParams(JsonNode params) {
    String zone = zone(params);
    if (zone == null) return;
    try {
      ZoneId.of(zone);
    } catch (DateTimeException e) {
      throw new MappingValidationException("parse-date hook: 'zone' is not a time zone: \""
          + zone + "\" (for example Europe/Paris or UTC)");
    }
  }

  /** The declared zone, or null when there is none: missing, null and blank all count as none, as
   *  they count as missing for KotlinCellHook's `script`. */
  private static String zone(JsonNode params) {
    JsonNode zone = params.get("zone");
    return zone == null || zone.isNull() || zone.asText().isBlank() ? null : zone.asText();
  }

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState s) {
    String col = params.path("column").asText();
    Object v = row.get(col);
    if (v == null) return;
    // Already a temporal type — nothing to do. Any java.time value or java.util.Date (a kotlin hook
    // may hand over an Instant or a Timestamp): read as text it would miss, be nulled and counted.
    if (v instanceof TemporalAccessor || v instanceof Date) return;

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
        String zone = zone(params);
        if (zone != null && parsed instanceof LocalDateTime local)
          parsed = local.atZone(ZoneId.of(zone)).toOffsetDateTime();
        row.put(col, parsed);
        return;
      }
    }

    // None matched — soft failure
    s.counters().cellErrors().incrementAndGet();
    row.put(col, null);
  }
}
