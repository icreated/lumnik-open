package io.lumnik.hub.connector.mapping.hooks.row;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.ScalarParams;
import io.lumnik.hub.connector.mapping.hooks.ColumnEffect;
import io.lumnik.hub.connector.mapping.hooks.HookCatalog;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import io.lumnik.hub.connector.mapping.hooks.RowHook;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
@HookId("aggregator")
public class AggregatorHook implements RowHook {

  /** What a merge folds into — the cases of {@link #mergeColumn}. */
  private static final List<String> INTO = List.of("array", "set", "csv");

  /** The keys of one merge entry, the keys {@link #mergeColumn} reads (#1487). */
  private static final Set<String> MERGE_KEYS = Set.of("collect", "into", "dedup", "sep");

  /**
   * {@code merge} is a map of output column to {@code {collect, into, dedup, sep}}, and {@code collect} names the source
   * column. Anything else read as {@code ""} and collected nothing, in silence (#1441).
   */
  @Override
  public void validateParams(JsonNode params) {
    // #1472: the key column and keep-first are column names; a list or a map read as "", a null as "null", and
    // blank text names no column. #1482: without the key every row fell into one group, without a word.
    if (!params.has("emit-on-change")) {
      throw new MappingValidationException("aggregator hook: 'emit-on-change' is required — the column whose change "
          + "closes a group");
    }
    ScalarParams.columnName("aggregator hook", "emit-on-change", params.get("emit-on-change"));
    if (params.has("keep-first")) ScalarParams.columnNames("aggregator hook", "keep-first", params.get("keep-first"));
    JsonNode merge = params.path("merge");
    if (merge.isMissingNode() || merge.isNull()) return;   // a bare `merge:` is absent, the house convention
    if (!merge.isObject()) {
      throw new MappingValidationException("aggregator hook: 'merge' must be a map of column to "
          + "{collect, into, dedup, sep} (got " + MappingValidationException.typeName(merge) + ")");
    }
    merge.fields().forEachRemaining(e -> {
      JsonNode spec = e.getValue();
      if (!spec.isObject()) {
        throw new MappingValidationException("aggregator hook: 'merge." + e.getKey()
            + "' must be a map {collect, into, dedup, sep} (got " + MappingValidationException.typeName(spec) + ")");
      }
      HookCatalog.refuseUnknownKeys("aggregator hook", "merge." + e.getKey() + ".", spec, MERGE_KEYS);
      if (spec.has("collect")) {
        ScalarParams.text("aggregator hook", "merge." + e.getKey() + ".collect", spec.get("collect"));
      }
      // #1482: an unknown word folded into an array, in silence.
      ScalarParams.optionalWord("aggregator hook", "merge." + e.getKey() + ".into", spec.path("into"), "array", INTO);
      ScalarParams.optionalText("aggregator hook", "merge." + e.getKey() + ".sep", spec.path("sep"), null);
      // #1486: "yes" or a list read as false — duplicates kept, in silence.
      ScalarParams.optionalBoolean("aggregator hook", "merge." + e.getKey() + ".dedup", spec.path("dedup"), false);
    });
  }

  @Override
  public List<Map<String, Object>> apply(Map<String, Object> row, JsonNode params, MappingState state) {
    String keyCol = params.path("emit-on-change").asText();
    String stateKey = "aggregator:" + keyCol;
    var agg = state.aggregator(stateKey);
    String newKey = String.valueOf(row.get(keyCol));

    List<Map<String, Object>> out = new ArrayList<>();
    if (agg.currentKey() != null && !agg.currentKey().equals(newKey)) {
      out.add(buildEmission(agg.buffer(), params));
      agg.clearBuffer();
      state.counters().aggregatorEmissions().incrementAndGet();
    }
    agg.currentKey(newKey);
    agg.addToBuffer(row);
    return out;
  }

  @Override
  public List<Map<String, Object>> flush(JsonNode params, MappingState state) {
    String keyCol = params.path("emit-on-change").asText();
    String stateKey = "aggregator:" + keyCol;
    var agg = state.aggregator(stateKey);
    if (agg.buffer().isEmpty()) return List.of();
    Map<String, Object> emission = buildEmission(agg.buffer(), params);
    agg.clearBuffer();
    state.counters().aggregatorEmissions().incrementAndGet();
    return List.of(emission);
  }

  private Map<String, Object> buildEmission(List<Map<String, Object>> buf, JsonNode params) {
    Map<String, Object> result = new LinkedHashMap<>();
    applyKeepFirst(result, buf, params);
    applyMerge(result, buf, params);
    return result;
  }

  /** Copy the named columns straight from the first buffered row. */
  private void applyKeepFirst(Map<String, Object> result, List<Map<String, Object>> buf, JsonNode params) {
    if (!params.has("keep-first") || buf.isEmpty()) return;
    Map<String, Object> first = buf.get(0);
    for (JsonNode col : params.get("keep-first")) {
      String c = col.asText();
      result.put(c, first.get(c));
    }
  }

  /** Fold each merge target across the buffer into an array / set / csv value. */
  private void applyMerge(Map<String, Object> result, List<Map<String, Object>> buf, JsonNode params) {
    if (!params.has("merge")) return;
    params.get("merge").fields().forEachRemaining(entry ->
        result.put(entry.getKey(), mergeColumn(buf, entry.getValue())));
  }

  private Object mergeColumn(List<Map<String, Object>> buf, JsonNode mergeSpec) {
    String sourceCol = mergeSpec.path("collect").asText();
    String into = mergeSpec.path("into").asText("array");
    boolean dedup = mergeSpec.path("dedup").asBoolean(false);
    String sep = mergeSpec.path("sep").asText(",");

    List<Object> collected = collect(buf, sourceCol, dedup);
    return switch (into) {
      case "csv" -> collected.stream().map(String::valueOf).collect(Collectors.joining(sep));
      case "set" -> new LinkedHashSet<>(collected);
      default -> collected;
    };
  }

  private List<Object> collect(List<Map<String, Object>> buf, String sourceCol, boolean dedup) {
    List<Object> collected = new ArrayList<>();
    Set<Object> seen = new LinkedHashSet<>();
    for (var r : buf) {
      Object v = r.get(sourceCol);
      if (v == null) continue;
      if (!dedup || seen.add(v)) collected.add(v);
    }
    return collected;
  }

  @Override
  public Set<String> params() {
    return Set.of("emit-on-change", "keep-first", "merge");
  }

  @Override
  public ColumnEffect columns(JsonNode params) {
    ColumnEffect.Builder b = ColumnEffect.builder().reads("emit-on-change", params.path("emit-on-change"))
        .readsEach("keep-first", params.path("keep-first"));
    params.path("merge").fields().forEachRemaining(e ->
        b.reads("merge." + e.getKey() + ".collect", e.getValue().path("collect")).adds(e.getKey()));
    return b.build();
  }
}
