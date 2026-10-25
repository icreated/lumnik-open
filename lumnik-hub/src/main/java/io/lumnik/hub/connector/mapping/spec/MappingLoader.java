package io.lumnik.hub.connector.mapping.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.ScalarParams;
import io.lumnik.hub.manifest.HubYaml;
import io.lumnik.hub.sql.SqlIdentifiers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MappingLoader {

  private static final ObjectMapper YAML = HubYaml.mapper();

  private MappingLoader() {}

  public static MappingSpec load(String yaml) {
    JsonNode root;
    try {
      root = YAML.readTree(yaml);
    } catch (Exception e) {
      throw new MappingValidationException("YAML parse error: " + e.getMessage(), e);
    }

    String id = requireText(root, "id");
    int version = requireInt(root, "version");
    MappingTarget target = parseTarget(root.get("target"));

    MappingSpec.StreamMode mode = "sorted-stream".equals(
        ScalarParams.optionalWord("mapping", "mode", root.path("mode"), "stream", List.of("stream", "sorted-stream")))
        ? MappingSpec.StreamMode.SORTED_STREAM : MappingSpec.StreamMode.STREAM;

    String description = ScalarParams.optionalText("mapping", "description", root.path("description"), "");

    return new MappingSpec(
        id, version, target, mode, description,
        HookInvocation.parseList(root, "dataset"),
        HookInvocation.parseList(root, "row"),
        HookInvocation.parseList(root, "cell"),
        ErrorPolicy.parse(root.path("on-error"))
    );
  }

  private static MappingTarget parseTarget(JsonNode node) {
    if (node == null || node.isNull()) {
      throw new MappingValidationException("target is required");
    }
    // Legacy: string
    if (node.isTextual()) {
      return MappingTarget.jpaOnly(node.asText());
    }
    // Modern: object
    if (!node.isObject()) {
      throw new MappingValidationException("target must be a string or object");
    }
    MappingTarget.Rag rag = node.has("rag") ? parseRag(node.get("rag")) : null;
    MappingTarget.Table table = node.has("table") ? parseTable(node.get("table")) : null;
    MappingTarget.Jpa jpa = node.has("jpa")
        ? new MappingTarget.Jpa(ScalarParams.optionalText("target.jpa", "entity", node.get("jpa").path("entity"), ""))
        : null;
    if (rag == null && table == null && jpa == null) {
      throw new MappingValidationException("target must specify at least one of: rag, table, jpa, hybrid-jsonb");
    }
    // hybridJsonb intentionally null here: REST connectors construct MappingTarget directly
    // (see RestEndpointWriter), not via the YAML mapping loader path.
    return new MappingTarget(rag, table, jpa, null);
  }

  private static MappingTarget.Rag parseRag(JsonNode r) {
    Map<String, String> meta = new LinkedHashMap<>();
    if (r.has("metadata")) {
      r.get("metadata").fields().forEachRemaining(e ->
          meta.put(e.getKey(), ScalarParams.text("target.rag.metadata", e.getKey(), e.getValue())));
    }
    return new MappingTarget.Rag(
        ScalarParams.optionalText("target.rag", "corpus", r.path("corpus"), ""),
        ScalarParams.optionalText("target.rag", "chunk-template", r.path("chunk-template"), ""),
        meta,
        ScalarParams.optionalText("target.rag", "embedding-model", r.path("embedding-model"), null)
    );
  }

  /**
   * #1472: each knob is read typed — asText() read a list or a map as "" (an index over no column, an unknown enum
   * constant thrown raw) and a null as the word "null" (a table named null).
   */
  private static MappingTarget.Table parseTable(JsonNode t) {
    MappingTarget.OnConflict oc = switch (
        ScalarParams.optionalText("target.table", "on-conflict", t.path("on-conflict"), "skip").toLowerCase(Locale.ROOT)) {
      case "skip" -> MappingTarget.OnConflict.SKIP;
      case "update" -> MappingTarget.OnConflict.UPDATE;
      case "error" -> MappingTarget.OnConflict.ERROR;
      default -> throw new MappingValidationException("target.table: 'on-conflict' must be skip, update or error");
    };
    JsonNode indexes = t.path("indexes");
    if (!indexes.isMissingNode() && !indexes.isNull() && !indexes.isArray()) {
      throw new MappingValidationException("target.table: 'indexes' must be a list of { columns: [...], unique } (got "
          + MappingValidationException.typeName(indexes) + ")");
    }
    List<MappingTarget.Index> idx = new ArrayList<>();
    for (int n = 0; n < indexes.size(); n++) {
      JsonNode i = indexes.get(n);
      if (!i.isObject()) {
        throw new MappingValidationException("target.table: 'indexes[" + n + "]' must be { columns: [...], unique } (got "
            + MappingValidationException.typeName(i) + ")");
      }
      List<String> cols = ScalarParams.columnNames("target.table.indexes[" + n + "]", "columns", i.path("columns"));
      idx.add(new MappingTarget.Index(cols,
          ScalarParams.optionalBoolean("target.table.indexes[" + n + "]", "unique", i.path("unique"), false)));
    }
    String schema = ScalarParams.optionalText("target.table", "schema", t.path("schema"), "public");
    String name = ScalarParams.optionalText("target.table", "name", t.path("name"), "");
    requireIdentifier(schema, "target.table.schema");
    requireIdentifier(name, "target.table.name");
    boolean autoSchema = ScalarParams.optionalBoolean("target.table", "auto-schema", t.path("auto-schema"), true);
    return new MappingTarget.Table(schema, name, autoSchema, oc, idx);
  }

  /**
   * DDL-injection boundary (layer 2, alongside the escaping fix in {@code RlsDdl}): {@code schema}
   * and {@code name} feed straight into DDL that {@code DynamicTableWriter}/{@code HybridJsonbWriter}
   * execute on the BYPASSRLS admin datasource, so refuse anything that is not a plain identifier
   * here, before it reaches that DDL.
   */
  private static void requireIdentifier(String value, String field) {
    if (!SqlIdentifiers.isPlainIdentifier(value)) {
      throw new MappingValidationException(
          field + " must be a plain identifier (letters, digits, underscore; not starting with a digit): " + value);
    }
  }

  private static String requireText(JsonNode n, String key) {
    JsonNode v = n.get(key);
    if (v == null || v.isNull() || v.asText().isBlank()) {
      throw new MappingValidationException(key + " is required");
    }
    return v.asText();
  }

  private static int requireInt(JsonNode n, String key) {
    JsonNode v = n.get(key);
    if (v == null || !v.canConvertToInt()) {
      throw new MappingValidationException(key + " must be an int");
    }
    return v.intValue();
  }
}
