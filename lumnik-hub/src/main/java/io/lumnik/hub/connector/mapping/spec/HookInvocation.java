package io.lumnik.hub.connector.mapping.spec;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingValidationException;

import java.util.ArrayList;
import java.util.List;

public record HookInvocation(
    String name,     // e.g. "clean", "parse-date", "aggregator"
    JsonNode params  // raw params (each hook parses its own schema)
) {

  /**
   * Parse a tier's YAML array of single-key hook objects into invocations, in one place both
   * manifest loaders share. It existed in two — {@code MappingLoader.parseHooks} threw on a bad
   * entry, {@code CsvFileConnectorType.parseHooks} SILENTLY DROPPED it, so the same malformed hook
   * refused a jdbc/manifest mapping and vanished from a csv one, taking the integrator's declared
   * step with it. One owner, one behaviour: an entry that is not a single-key object
   * ({@code - [a, b]}, {@code - {a: 1, b: 2}}) is refused, both sides.
   *
   * <p>The hook's VALUE (its params) is intentionally NOT shape-checked here: {@code skip-empty-rows:
   * true} carries a boolean, {@code map-columns: {…}} an object, {@code parse-date:} a null (no
   * knobs). Each hook reads its own params — a hook that requires a map refuses a non-map in its own
   * {@code validateParams}, where the expected shape is known; a blanket "must be a map" here would
   * reject the legitimate scalar-flag hooks.
   */
  public static List<HookInvocation> parseList(JsonNode arr) {
    if (arr == null || !arr.isArray()) return List.of();
    List<HookInvocation> out = new ArrayList<>(arr.size());
    for (JsonNode entry : arr) {
      if (!entry.isObject() || entry.size() != 1) {
        throw new MappingValidationException(
            "each hook must be an object with one key, got: " + entry);
      }
      var field = entry.fields().next();
      out.add(new HookInvocation(field.getKey(), field.getValue()));
    }
    return out;
  }
}
