package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
      try {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(fmt);
        boolean hasTime = fmt.contains("H") || fmt.contains("h") || fmt.contains("m") || fmt.contains("s");
        if (hasTime) {
          row.put(col, LocalDateTime.parse(str, formatter));
        } else {
          row.put(col, LocalDate.parse(str, formatter));
        }
        return;
      } catch (DateTimeParseException ignored) {
        // try next format
      }
    }

    // None matched — soft failure
    s.counters().cellErrors().incrementAndGet();
    row.put(col, null);
  }
}
