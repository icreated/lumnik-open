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
 * Converts curly/typographic Unicode punctuation (from Word, Excel) to plain ASCII equivalents.
 */
@ApplicationScoped
@HookId("replace-smart-quotes")
public class ReplaceSmartQuotesHook implements CellHook {

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState s) {
    for (String col : CleanHook.columnList(params, row)) {
      Object v = row.get(col);
      if (!(v instanceof String str)) continue;
      row.put(col, transform(str));
    }
  }

  private String transform(String input) {
    return input
        .replace('‘', '\'')   // LEFT SINGLE QUOTATION MARK
        .replace('’', '\'')   // RIGHT SINGLE QUOTATION MARK / apostrophe
        .replace('“', '"')    // LEFT DOUBLE QUOTATION MARK
        .replace('”', '"')    // RIGHT DOUBLE QUOTATION MARK
        .replace('«', '"')    // LEFT-POINTING DOUBLE ANGLE QUOTATION MARK «
        .replace('»', '"')    // RIGHT-POINTING DOUBLE ANGLE QUOTATION MARK »
        .replace('–', '-')    // EN DASH
        .replace('—', '-')    // EM DASH
        .replace("…", "...")  // HORIZONTAL ELLIPSIS
        .replace('\u00A0', ' ');   // NON-BREAKING SPACE
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
