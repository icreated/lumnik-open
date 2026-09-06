package io.lumnik.hub.connector.mapping.hooks.row;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MapColumnsRules;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import io.lumnik.hub.connector.mapping.hooks.RowHook;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * <p>Do NOT read the template mapping path as the reason: it is dormant — no
 * {@code META-INF/mapping} ships, {@code MappingTemplateRegistry.register} has no main-code
 * caller, and {@code ChunkExecutor} resolves through {@code MappingResolverStub}. An earlier
 * draft of this comment named it, which would have sent the next cleanup to delete a
 * load-bearing guard after correctly finding that path dead.
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
    Map<String, Object> out = new HashMap<>(row);
    if (params.has("rename")) {
      JsonNode rename = params.get("rename");
      rename.fields().forEachRemaining(e -> {
        // containsKey, not "remove returned non-null": a column PRESENT with a null value must be
        // renamed like any other. Testing the value instead removed the source key and created no
        // target, so the column vanished — and DynamicTableWriter types its DDL from the keys a
        // row carries, which made the column's very existence depend on its first value.
        if (!out.containsKey(e.getKey())) return;
        Object v = out.remove(e.getKey());
        String target = e.getValue().asText();
        // The target is already in this ROW. No manifest check could have caught it: the collision
        // is with the data, not with another rename entry. The rename still wins — counting makes
        // the overwritten value visible, it does not change which one lands.
        //
        // This is the FIRST counting site outside hooks/cell/, and the shape it adds to
        // cell_errors is 'overwritten': the cell holds a value the source really sent, from
        // another column. (Distinct from 'replaced', which is a value the source never sent.)
        // The tier of the HOOK is not the tier of the EVENT — one lost value in a row that still
        // lands is the cell_errors contract, where state.dropped() would quarantine a good row.
        if (out.containsKey(target)) s.counters().cellErrors().incrementAndGet();
        out.put(target, v);
      });
    }
    if (params.has("drop")) {
      JsonNode drop = params.get("drop");
      for (JsonNode col : drop) {
        out.remove(col.asText());
      }
    }
    return List.of(out);
  }

  /** Each door renders the shared rule its own way; this one throws on the first violation. */
  private static void refuse(List<MapColumnsRules.Violation> violations) {
    if (!violations.isEmpty()) {
      var v = violations.get(0);
      throw new MappingValidationException("map-columns hook: '" + v.path() + "' " + v.reason());
    }
  }
}
