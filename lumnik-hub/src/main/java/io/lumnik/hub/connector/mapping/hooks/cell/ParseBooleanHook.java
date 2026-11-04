package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.ScalarParams;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.ColumnEffect;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A legacy flag — {@code 0/1}, {@code O/N}, {@code Oui/Non} — as a Boolean, which the writer types as a BOOLEAN
 * column (#1760). Trimmed and case-insensitive; {@code true}/{@code false} lists replace the defaults. A value in
 * neither list is emptied and counted, never guessed; an empty or blank cell stays empty; a Boolean is kept.
 */
@ApplicationScoped
@HookId("parse-boolean")
public class ParseBooleanHook implements CellHook {

  private static final Set<String> TRUE = Set.of("1", "true", "yes", "y", "oui", "o", "vrai", "t");
  private static final Set<String> FALSE = Set.of("0", "false", "no", "n", "non", "faux", "f");

  /**
   * The words of the last params seen — a single slot, as {@link RegexExtractHook} keeps its pattern, so a run's
   * cells do not each rebuild the lists; a concurrent run with other words overwrites it, never grows it.
   */
  private volatile Words cache;

  private record Words(JsonNode params, Set<String> yes, Set<String> no) {}

  @Override
  public void validateParams(JsonNode params) {
    Words w = words(params);
    if (w.yes().contains("")) throw blankWord("true");
    if (w.no().contains("")) throw blankWord("false");
    for (String no : w.no()) {
      if (w.yes().contains(no)) throw new MappingValidationException("parse-boolean hook: '" + no + "' is in both true and false");
    }
  }

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState s) {
    String col = params.path("column").asText();
    Object v = row.get(col);
    if (v == null || v instanceof Boolean || BlankCell.emptied(row, col, v)) return;
    String word = v.toString().strip().toLowerCase(Locale.ROOT);
    Words w = cached(params);
    Boolean flag = w.yes().contains(word) ? Boolean.TRUE : w.no().contains(word) ? Boolean.FALSE : null;
    if (flag == null) s.counters().cellErrors().incrementAndGet();
    row.put(col, flag);
  }

  private static MappingValidationException blankWord(String key) {
    return new MappingValidationException("parse-boolean hook: '" + key + "' holds a blank word — a blank cell stays empty");
  }

  private Words cached(JsonNode params) {
    Words w = cache;
    // The engine hands the same params node to every row: identity first, a deep equals only when it differs.
    if (w == null || (w.params() != params && !w.params().equals(params))) {
      w = words(params);
      cache = w;
    }
    return w;
  }

  private static Words words(JsonNode params) {
    return new Words(params, list(params, "true", TRUE), list(params, "false", FALSE));
  }

  private static Set<String> list(JsonNode params, String key, Set<String> defaults) {
    JsonNode declared = params.path(key);
    if (declared.isMissingNode()) return defaults;
    return ScalarParams.textList("parse-boolean hook", key, declared).stream()
        .map(word -> word.strip().toLowerCase(Locale.ROOT))
        .collect(Collectors.toUnmodifiableSet());
  }

  @Override
  public Set<String> params() {
    return Set.of("column", "true", "false");
  }

  @Override
  public ColumnEffect columns(JsonNode params) {
    return ColumnEffect.readsColumn(params);
  }
}
