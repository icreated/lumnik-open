package io.lumnik.hub.connector.mapping.hooks.row;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MapColumnsRules;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.hooks.ColumnEffect;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import io.lumnik.hub.connector.mapping.hooks.RowHook;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renames columns, then drops them — both optional, and drop runs LAST. So a {@code drop} entry
 * must name the column as it is BY THEN: for a column this hook has just renamed, that is its new
 * name. Naming the old one removes nothing and leaves the column in the table under the new name,
 * which is the opposite of what was asked ({@code MapColumnsRenameTest} pins both directions).
 *
 * <p>Rules in {@link io.lumnik.hub.connector.mapping.MapColumnsRules}, which names every door
 * that shares them. This one throws on the first violation, and it keeps checking on every row
 * even though two apply-time doors now refuse first — because there is a live door that refuses
 * nothing: {@code ConnectorAdminResource}'s POST/PUT on {@code /api/platform/connectors} sets the
 * config with no {@code ConnectorValidationService} call at all (it injects none), and the TUI's
 * flat-field {@code connector apply} drives it. A csv connector written that way reaches the run
 * having passed no validator, and this hook is the only thing between it and a lost column.
 *
 * <p>Renaming a source column that IS a system column is never refused — that is precisely what
 * this knob exists for. Only a rename INTO one is.
 */
@ApplicationScoped
@HookId("map-columns")
public class MapColumnsHook implements RowHook {

  /**
   * Param-shape rules run ONCE per spec here, not per row — the manifest node cannot change
   * between rows. {@code apply()} below trusts this ran (the engine calls it before any row).
   */
  @Override
  public void validateParams(JsonNode params) {
    refuse(MapColumnsRules.checkRename(params.path("rename")));
    refuse(MapColumnsRules.checkDrop(params.path("drop")));
  }

  @Override
  public List<Map<String, Object>> apply(Map<String, Object> row, JsonNode params, MappingState s) {
    Map<String, Object> out = params.has("rename")
        ? renamed(row, params.get("rename"), s)
        : new LinkedHashMap<>(row);
    if (params.has("drop")) {
      JsonNode drop = params.get("drop");
      for (JsonNode col : drop) {
        out.remove(col.asText());
      }
    }
    return List.of(out);
  }

  /**
   * One slot per row column, in source order, plus an index by the column's CURRENT name — a
   * rename mutates a slot's key in place (keeping its position) or, on collision, tombstones the
   * source and overwrites the target slot's value, then the final map is built once at the end.
   * {@code O(columns + renames)}, one allocation for the result, versus rebuilding a
   * {@code LinkedHashMap} per non-colliding rename (#526 review round 2).
   */
  private static Map<String, Object> renamed(Map<String, Object> row, JsonNode rename, MappingState s) {
    List<Slot> slots = new ArrayList<>(row.size());
    Map<String, Slot> byKey = new HashMap<>();
    row.forEach((k, v) -> {
      Slot slot = new Slot(k, v);
      slots.add(slot);
      byKey.put(k, slot);
    });

    rename.fields().forEachRemaining(e -> {
      // byKey.get, not "was the column ever seen": a column PRESENT with a null value must be
      // renamed like any other. Testing the value instead removed the source key and created no
      // target, so the column vanished — and DynamicTableWriter types its DDL from the keys a
      // row carries, which made the column's very existence depend on its first value.
      String oldKey = e.getKey();
      Slot src = byKey.get(oldKey);
      if (src == null) return;
      String target = e.getValue().asText();
      // A rename to its OWN name touches nothing — not a data collision. Checking anyway would
      // count a false loss, since the "old" and "new" name are the same key (#526 review).
      if (target.equals(oldKey)) return;
      Slot existing = byKey.get(target);
      if (existing != null) {
        // The target is already live at THIS STEP. No manifest check could have caught it: the
        // collision is with the data (or an earlier rename's own output — renames run in
        // manifest order, each against the one before, so a chain or a cycle sees exactly what
        // it did before this fix), not with another rename entry by name alone. The rename still
        // wins — counting makes the overwritten value visible, it does not change which one lands.
        //
        // This is the FIRST counting site outside hooks/cell/, and the shape it adds to
        // cell_errors is 'overwritten': the cell holds a value the source really sent, from
        // another column. (Distinct from 'replaced', which is a value the source never sent.)
        // The tier of the HOOK is not the tier of the EVENT — one lost value in a row that still
        // lands is the cell_errors contract, where state.dropped() would quarantine a good row.
        s.counters().cellErrors().incrementAndGet();
        existing.value = src.value; // the TARGET's slot keeps its position; only the value moves
        src.tombstoned = true;
        byKey.remove(oldKey);
      } else {
        src.key = target; // no collision: rename the slot in place, so its position doesn't move
        byKey.remove(oldKey);
        byKey.put(target, src);
      }
    });

    Map<String, Object> out = new LinkedHashMap<>();
    for (Slot slot : slots) {
      if (!slot.tombstoned) out.put(slot.key, slot.value);
    }
    return out;
  }

  /** A row column a rename can relocate ({@code key}) or replace ({@code value}) in place. */
  private static final class Slot {
    String key;
    Object value;
    boolean tombstoned;

    Slot(String key, Object value) {
      this.key = key;
      this.value = value;
    }
  }

  /** Each door renders the shared rule its own way; this one throws on the first violation. */
  private static void refuse(List<MapColumnsRules.Violation> violations) {
    if (!violations.isEmpty()) {
      var v = violations.get(0);
      throw new MappingValidationException("map-columns hook: '" + v.path() + "' " + v.reason());
    }
  }

  @Override
  public Set<String> params() {
    return Set.of("rename", "drop");
  }

  @Override
  public ColumnEffect columns(JsonNode params) {
    // In apply's order: each rename in turn (its source read, then moved), then the drops.
    ColumnEffect.Builder b = ColumnEffect.builder();
    params.path("rename").fields().forEachRemaining(e -> b.reads("rename." + e.getKey(), e.getKey())
        .removes(e.getKey()).adds(e.getValue()));
    JsonNode drop = params.path("drop");
    for (int i = 0; i < drop.size(); i++) b.reads("drop[" + i + "]", drop.get(i)).removes(drop.get(i).asText());
    return b.build();
  }
}
