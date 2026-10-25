package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.ColumnEffect;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lowercases text cells.
 *
 * <p><b>Text cells only</b>, as every other text hook in this package does. Without the guard,
 * {@code toString()} turned a BYTEA into its Java identity string — the bytes gone — and a
 * {@code LocalDate} or {@code BigDecimal} into text, which downgraded the column's inferred SQL
 * type from DATE/NUMERIC to TEXT and broke range filters downstream. The manifest declared case
 * conversion, nothing else.
 *
 * <p><b>{@link Locale#ROOT}, not the JVM default.</b> Under a Turkish default locale {@code I}
 * lowercases to a dotless {@code i}, so an ASCII column value would change meaning according to
 * where the hub happens to run.
 */
@ApplicationScoped
@HookId("lower")
public class LowerHook implements CellHook {

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState s) {
    for (String col : CleanHook.columnList(params, row)) {
      Object v = row.get(col);
      if (!(v instanceof String str)) continue;
      row.put(col, str.toLowerCase(Locale.ROOT));
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
