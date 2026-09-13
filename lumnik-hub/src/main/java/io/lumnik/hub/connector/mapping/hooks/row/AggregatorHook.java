package io.lumnik.hub.connector.mapping.hooks.row;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import io.lumnik.hub.connector.mapping.hooks.RowHook;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
@HookId("aggregator")
public class AggregatorHook implements RowHook {

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
}
