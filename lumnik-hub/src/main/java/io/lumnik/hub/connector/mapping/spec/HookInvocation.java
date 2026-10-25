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
   * <p>The hook's VALUE (its params) is intentionally NOT shape-checked here: the parser splits entries;
   * {@code HookCatalog.refuseUnknownParams} refuses a block that is neither a map nor bare (#1492).
   */
  public static List<HookInvocation> parseList(JsonNode mapping, String tier) {
    JsonNode arr = requireList(mapping, tier);
    if (!arr.isArray()) return List.of();
    List<HookInvocation> out = new ArrayList<>(arr.size());
    for (int i = 0; i < arr.size(); i++) out.add(parse(arr.get(i), tier, i));
    return out;
  }

  /**
   * #1464: a tier is a list of hooks, or absent. Written as a map ({@code cell: { upper: … }}) or a scalar it used to
   * read as no hooks, at apply and at run time alike — the manifest applied clean and no hook ran. Absent and
   * {@code cell:} written empty stay allowed. Read by {@link #parseList} and by the csv-file validator.
   *
   * @return the tier's node: an array, missing or null
   */
  public static JsonNode requireList(JsonNode mapping, String tier) {
    JsonNode node = mapping.path(tier);
    if (node.isMissingNode() || node.isNull() || node.isArray()) return node;
    throw new MappingValidationException(tier + " must be a list of hooks, e.g. - upper: { columns: [name] } (got "
        + MappingValidationException.typeName(node) + ")");
  }

  /**
   * Entry {@code index} of {@code tier} — what {@link #parseList} reads, also read alone by the csv-file validator
   * (#1403). A refusal names the place and the shape, never the entry's content (#1474): constants and table
   * literals live there, and the message reaches {@code lm validate} and the run.
   */
  public static HookInvocation parse(JsonNode entry, String tier, int index) {
    if (!entry.isObject() || entry.size() != 1) {
      String shape = entry.isObject()
          ? "object with " + entry.size() + " keys"
          : MappingValidationException.typeName(entry);
      throw new MappingValidationException(
          "each hook must be an object with one key (" + tier + "[" + index + "]: got " + shape + ")");
    }
    var field = entry.fields().next();
    return new HookInvocation(field.getKey(), field.getValue());
  }
}
