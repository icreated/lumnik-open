package io.lumnik.hub.connector.rest.discovery;

import io.lumnik.hub.connector.naming.Identifiers;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;

/**
 * LLM augmentation for probe-generated manifests.
 *
 * <p>v1 implementation is a no-op pass-through: returns a deterministic YAML manifest
 * fragment built from the inferred structure, without calling an LLM. Wiring to the
 * actual LLM (Ollama / OpenAI / Claude) is deferred to a future task — it requires
 * an LlmPort abstraction that doesn't exist yet.
 */
@ApplicationScoped
public class LlmManifestAugmenter {

    /**
     * The endpoint fragment for one probed path — no connection, no scope: those are the connector's.
     * {@code itemsPath} is where the list was found, null when the document itself is the record.
     */
    public String augment(String name, String path,
                          JsonStructureInference.PaginationKind pag, String itemsPath, int items,
                          String uniqueKey, List<Map<String,Object>> promotes) {
        // safeName: sanitized identifier for the ext.* table prefix.
        String safeName = Identifiers.slug(name, "api");
        StringBuilder sb = new StringBuilder();
        sb.append("# Drafted by lm source rest probe — review before applying\n");
        // `id`, kebab — the endpoint contract, not the table's SQL one (see Identifiers.kebab).
        sb.append("- id: ").append(Identifiers.kebab(path, "endpoint")).append('\n');
        sb.append("  method: GET\n  path: ").append(path).append('\n');
        // Where the list is, whatever the kind: the paging kinds and none require it (#1652), and a draft that
        // knew it should not leave it out. Absent when no list was found — the document itself is the record.
        String kind = pag.toString().toLowerCase(Locale.ROOT).replace('_', '-');
        String where = itemsPath == null ? "" : ", response_path: " + yamlValue(itemsPath);
        if (pag == JsonStructureInference.PaginationKind.NONE) {
            // The whole list at once (#1650): what the probe saw, not a certainty.
            sb.append("  pagination: { kind: ").append(kind).append(where).append(" }   # ").append(items)
              .append(items == 1 ? " item" : " items").append(", no paging signal in the answer: if the source pages, declare how\n");
        } else {
            sb.append("  pagination: { kind: ").append(kind).append(where).append(" }\n");
        }
        sb.append("  target:\n    kind: hybrid\n    table: ext.").append(safeName).append('_').append(slug(path)).append('\n');
        sb.append("    unique_key: ").append(uniqueKey).append('\n');
        sb.append("    promote:\n");
        for (Map<String,Object> p : promotes) {
            sb.append("      - { name: ").append(p.get("name"))
              .append(", path: ").append(yamlValue((String) p.get("path")))
              .append(", type: ").append(p.get("type")).append(" }\n");
        }
        return sb.toString();
    }

    /**
     * A JSONPath as a value in a YAML flow map: bare when it is only {@code $}, letters, digits, {@code _} and dots;
     * double-quoted otherwise — {@code $['vet list']} bare is not YAML ({@code [ ] ,} are flow indicators).
     */
    static String yamlValue(String path) {
        if (path.matches("[$A-Za-z0-9_.]+")) return path;
        return '"' + path.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static String slug(String p) {
        return Identifiers.slug(p, "endpoint");
    }
}
