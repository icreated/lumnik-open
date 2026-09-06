package io.lumnik.hub.connector.mapping.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.sql.SqlIdentifiers;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MappingLoader {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

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

    MappingSpec.StreamMode mode = MappingSpec.StreamMode.STREAM;
    if (root.hasNonNull("mode")) {
      String m = root.get("mode").asText();
      if ("sorted-stream".equals(m)) mode = MappingSpec.StreamMode.SORTED_STREAM;
      else if (!"stream".equals(m)) throw new MappingValidationException("unknown mode: " + m);
    }

    String description = root.path("description").asText("");

    return new MappingSpec(
        id, version, target, mode, description,
        parseHooks(root.path("dataset")),
        parseHooks(root.path("row")),
        parseHooks(root.path("cell")),
        parseErrorPolicy(root.path("on-error"))
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
        ? new MappingTarget.Jpa(node.get("jpa").path("entity").asText())
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
      r.get("metadata").fields().forEachRemaining(e -> meta.put(e.getKey(), e.getValue().asText()));
    }
    String embModel = r.has("embedding-model") ? r.get("embedding-model").asText() : null;
    return new MappingTarget.Rag(
        r.path("corpus").asText(),
        r.path("chunk-template").asText(""),
        meta,
        embModel
    );
  }

  private static MappingTarget.Table parseTable(JsonNode t) {
    MappingTarget.OnConflict oc = MappingTarget.OnConflict.SKIP;
    if (t.has("on-conflict")) {
      oc = MappingTarget.OnConflict.valueOf(t.get("on-conflict").asText("skip").toUpperCase());
    }
    List<MappingTarget.Index> idx = new ArrayList<>();
    if (t.has("indexes")) {
      for (JsonNode i : t.get("indexes")) {
        List<String> cols = new ArrayList<>();
        for (JsonNode c : i.get("columns")) cols.add(c.asText());
        idx.add(new MappingTarget.Index(cols, i.path("unique").asBoolean(false)));
      }
    }
    String schema = t.path("schema").asText("public");
    String name = t.path("name").asText();
    requireIdentifier(schema, "target.table.schema");
    requireIdentifier(name, "target.table.name");
    return new MappingTarget.Table(schema, name, t.path("auto-schema").asBoolean(true), oc, idx);
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

  private static List<HookInvocation> parseHooks(JsonNode arr) {
    return HookInvocation.parseList(arr);
  }

  private static ErrorPolicy parseErrorPolicy(JsonNode n) {
    if (n.isMissingNode() || n.isNull()) return ErrorPolicy.soft();
    // Honest boundary (tribunal B3): the policy is parsed and carried on MappingSpec,
    // but NO runtime consumer enforces it yet — rows follow the engine's default
    // behavior regardless. Warn so a template author doesn't rely on it silently.
    Logger.getLogger(MappingLoader.class).warn(
        "mapping template declares on-error, which is parsed but NOT enforced yet — "
            + "rows follow the engine's default error handling");
    String def = n.path("default").asText("soft");
    var defaultMode = ErrorPolicy.Mode.valueOf(def.toUpperCase());
    var fields = new HashMap<String, ErrorPolicy.Mode>();
    JsonNode fieldsNode = n.path("fields");
    if (fieldsNode.isObject()) {
      fieldsNode.fields().forEachRemaining(e ->
          fields.put(e.getKey(), ErrorPolicy.Mode.valueOf(e.getValue().asText("soft").toUpperCase())));
    }
    return new ErrorPolicy(defaultMode, fields);
  }
}
