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
 * First letter of each whitespace-separated word up, the rest down. Same type guard and locale
 * pinning as {@link LowerHook}, for the same two reasons.
 *
 * <p>It also NORMALISES whitespace — runs of spaces, tabs and newlines collapse to one space and
 * trailing space is dropped — which the manifest does not ask for. Left as it is deliberately:
 * changing it would make a re-ingest produce different values for unchanged source rows. Stated in
 * the integrator docs instead.
 */
@ApplicationScoped
@HookId("titlecase")
public class TitlecaseHook implements CellHook {

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState s) {
    for (String col : CleanHook.columnList(params, row)) {
      Object v = row.get(col);
      if (!(v instanceof String str)) continue;
      // Do NOT hoist this into a static Pattern. String.split does recompile it per call (the
      // fast path needs a 1-char regex; "\\s+" is three), but measured, a shared Pattern is 30%
      // SLOWER here: C2 scalar-replaces the throwaway Pattern/Matcher inside split, while a heap
      // one forces a real Matcher allocation. FixMojibakeHook's 2.6x hoist does not generalize —
      // that was String.matches, where the compile dominates a trivial match. Different API,
      // opposite answer.
      String[] words = str.split("\\s+");
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < words.length; i++) {
        if (i > 0) sb.append(' ');
        String w = words[i];
        if (!w.isEmpty()) {
          sb.append(Character.toUpperCase(w.charAt(0)));
          // Locale.ROOT for the same reason as lower/upper. Character.toUpperCase above is
          // already locale-independent; this call was not.
          if (w.length() > 1) sb.append(w.substring(1).toLowerCase(Locale.ROOT));
        }
      }
      row.put(col, sb.toString());
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
