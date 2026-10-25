package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.ColumnEffect;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.text.StringEscapeUtils;

import java.util.Map;
import java.util.Set;

/**
 * Decodes HTML/XML entities that ERP systems sometimes embed in CSV exports.
 * Example: "Coffee &amp; cream &lt;hot&gt;" → "Coffee & cream <hot>".
 */
@ApplicationScoped
@HookId("decode-html-entities")
public class DecodeHtmlEntitiesHook implements CellHook {

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState s) {
    for (String col : CleanHook.columnList(params, row)) {
      Object v = row.get(col);
      if (!(v instanceof String str)) continue;
      row.put(col, StringEscapeUtils.unescapeHtml4(str));
    }
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
