package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.ScalarParams;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.ColumnEffect;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Splits a single cell value into named fields (or an array).
 * Distinct from split-row which duplicates rows; this operates within a single row.
 */
@ApplicationScoped
@HookId("split")
public class SplitHook implements CellHook {

  @Override
  public void validateParams(JsonNode params) {
    // Refuse a mis-typed `into` once per spec, the way truncate refuses a bad `length`: hook params
    // arrive unvalidated (MappingLoader passes them through), and Jackson fails the two mis-typings
    // differently, both badly. A scalar `into: x` is a TextNode whose size() is 0, so the loop
    // never ran and a manifest that declared columns produced none, in silence. An object
    // `into: {a: b}` has size() 1 but get(0) is null on an ObjectNode, so `names.get(i).asText()`
    // threw a bare NPE out of the cell tier and ChunkExecutor failed the whole chunk on it —
    // naming neither the hook nor the knob. Refused here, before any row.
    JsonNode into = params.path("into");
    // A bare `into:` (null) or an absent one reads as "no into declared" — the house convention.
    // Only a PRESENT non-list is the mis-typing to refuse (a scalar silently produced no columns,
    // an object threw a bare NPE out of the cell tier) — and, #1472, a name that is a list or a map
    // (read as "") or a bare `-` (the column "null").
    if (!into.isMissingNode() && !into.isNull()) ScalarParams.columnNames("split hook", "into", into);
    // #1482: a list or a map `by` read as "" and split the value into its characters; bare is the default, as before.
    // #1485: and so did an empty `by`.
    ScalarParams.optionalSeparator("split hook", "by", params.path("by"), ",");
    // #1486: "yes" or a list read as false — the source cell left whole, in silence.
    ScalarParams.optionalBoolean("split hook", "into-array", params.path("into-array"), false);
  }

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState s) {
    String col = params.path("column").asText();
    if (col.isEmpty()) return;
    Object v = row.get(col);
    if (v == null) return;

    String by = params.path("by").asText(",");
    // Limit -1 keeps TRAILING empty parts. String.split's zero-limit default deletes them, so
    // "A,B,," used to arrive as two parts: two columns `into` declares were never created, and
    // under into-array the missing parts were destroyed outright with the source cell.
    String[] parts = v.toString().split(Pattern.quote(by), -1);

    JsonNode names = params.path("into");
    // isArray(), not has("into"): a bare `into:` (null) is absent (validateParams already refused a
    // present non-list), so it must fall through to into-array here, not be read as zero names.
    if (names.isArray()) {
      // Walk the DECLARED names, not min(names, parts): a name with no part still gets its column,
      // as null. The old loop stopped at the shorter of the two, so a short value left the column
      // absent entirely — and DynamicTableWriter types its DDL from the keys a row carries, so the
      // column's very existence depended on the first chunk's data.
      for (int i = 0; i < names.size(); i++) {
        row.put(names.get(i).asText(), i < parts.length ? parts[i].trim() : null);
      }
      // The value did not have the shape `into` declares — surplus parts have nowhere to go, or
      // declared names went unfilled. Counted, not quarantined: this is the per-VALUE tier and its
      // established shape is ParseDateHook — write the row, count the cell (`cell_errors` on
      // `lm run get`). Quarantining the row here would be a louder contract than any other cell
      // hook has. What must never happen again is the third option: neither, in silence.
      if (parts.length != names.size()) {
        s.counters().cellErrors().incrementAndGet();
      }
    } else if (params.path("into-array").asBoolean(false)) {
      List<String> arr = new ArrayList<>(parts.length);
      for (String p : parts) arr.add(p.trim());
      row.put(col, arr);
    }
  }

  @Override
  public Set<String> params() {
    return Set.of("column", "by", "into", "into-array");
  }

  @Override
  public ColumnEffect columns(JsonNode params) {
    ColumnEffect.Builder b = ColumnEffect.builder().reads("column", params.path("column"));
    params.path("into").forEach(b::adds);
    return b.build();
  }
}
