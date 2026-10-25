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

    /** The endpoint fragment for one probed path — no connection, no scope: those are the connector's. */
    public String augment(String name, String path,
                          JsonStructureInference.PaginationKind pag,
                          String uniqueKey, List<Map<String,Object>> promotes) {
        // safeName: sanitized identifier for the ext.* table prefix.
        String safeName = Identifiers.slug(name, "api");
        StringBuilder sb = new StringBuilder();
        sb.append("# Drafted by lm source rest probe — review before applying\n");
        // `id`, kebab — the endpoint contract, not the table's SQL one (see Identifiers.kebab).
        sb.append("- id: ").append(Identifiers.kebab(path, "endpoint")).append('\n');
        sb.append("  method: GET\n  path: ").append(path).append('\n');
        sb.append("  pagination: { kind: ").append(pag.toString().toLowerCase().replace('_', '-')).append(" }\n");
        sb.append("  target:\n    kind: hybrid\n    table: ext.").append(safeName).append('_').append(slug(path)).append('\n');
        sb.append("    unique_key: ").append(uniqueKey).append('\n');
        sb.append("    promote:\n");
        for (Map<String,Object> p : promotes) {
            sb.append("      - { name: ").append(p.get("name"))
              .append(", path: ").append(p.get("path"))
              .append(", type: ").append(p.get("type")).append(" }\n");
        }
        return sb.toString();
    }

    private static String slug(String p) {
        return Identifiers.slug(p, "endpoint");
    }
}
