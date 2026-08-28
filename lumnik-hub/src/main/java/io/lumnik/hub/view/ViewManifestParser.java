package io.lumnik.hub.view;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.lumnik.hub.manifest.ManifestKeys;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Parses a kind:View YAML manifest into a ViewManifest. Structural only — the ViewResolver validates columns. */
public class ViewManifestParser {

    private static final YAMLMapper MAPPER = new YAMLMapper();

    // The closed inventory (C3). Kept beside parse() on purpose: a field added there without a key
    // added here is the drift that would make this check cry wolf, and the tests pin both halves.
    // apiVersion is DECLARED though parse() ignores it: every shipped manifest carries the
    // apps.lumnik.io/v1 envelope (docs/demo/wow-customer/*.yaml among them), so refusing it
    // would cry wolf on the WOW demo itself — the very failure mode the C3 verdict named.
    private static final String METADATA = "metadata";

    private static final Set<String> ROOT_KEYS = Set.of("apiVersion", "kind", METADATA, "spec");
    private static final Set<String> METADATA_KEYS = Set.of("name");
    private static final Set<String> SPEC_KEYS = Set.of("scope", "table", "title", "columns");

    private static final Map<String, Set<String>> ALLOWED_BY_PATH = new LinkedHashMap<>();
    static {
        ALLOWED_BY_PATH.put("", ROOT_KEYS);
        ALLOWED_BY_PATH.put(METADATA, METADATA_KEYS);
        ALLOWED_BY_PATH.put("spec", SPEC_KEYS);
    }

    /**
     * Every key the manifest declares that this parser does not honor, as dotted paths in document
     * order — empty when the manifest is clean, and empty when it does not parse at all (that is
     * {@link #parse}'s error to raise, not this check's).
     *
     * <p>Reporting only: saved views live in the store as YAML, so refusing here would break
     * reading a view already saved with an extra key. The apply door refuses; the read stays kind.
     */
    public List<String> unknownKeys(String yaml) {
        JsonNode root;
        try {
            root = MAPPER.readTree(yaml);
        } catch (Exception e) {
            return List.of();
        }
        return ManifestKeys.unknownKeys(root, ALLOWED_BY_PATH);
    }

    /**
     * The honored surface, for a refusal that teaches — <b>derived</b> from the inventory above, so
     * the message can never contradict what is actually accepted. Hand-typed, it drifts on the first
     * edit, and the first casualty is {@code apiVersion}: accepted, unused, and easy to leave out of
     * a sentence.
     */
    public static String honoredSurface() {
        return "root {" + sorted(ROOT_KEYS) + "}, metadata {" + sorted(METADATA_KEYS)
            + "}, spec {" + sorted(SPEC_KEYS) + "}";
    }

    private static String sorted(Set<String> keys) {
        return String.join(", ", new TreeSet<>(keys));
    }

    public ViewManifest parse(String yaml) {
        JsonNode root;
        try {
            root = MAPPER.readTree(yaml);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid kind:View manifest: " + e.getMessage(), e);
        }
        if (!"View".equals(ManifestKeys.text(root, "kind")))
            throw new IllegalArgumentException("kind must be 'View'");
        JsonNode meta = root.path(METADATA);
        JsonNode spec = root.path("spec");
        String name = ManifestKeys.text(meta, "name");
        String scope = ManifestKeys.text(spec, "scope");
        String table = ManifestKeys.text(spec, "table");
        String title = ManifestKeys.text(spec, "title");
        List<String> columns = new ArrayList<>();
        for (JsonNode c : spec.path("columns")) columns.add(c.asText());

        if (name == null || name.isBlank())
            throw new IllegalArgumentException("metadata.name is required");
        if (scope == null || scope.isBlank())
            throw new IllegalArgumentException("spec.scope is required");
        if (columns.isEmpty())
            throw new IllegalArgumentException("spec.columns must declare at least one column");

        // scope is normalized at the threshold: accidental whitespace must not leak into
        // authorization (a bound user would get a lying 403) or the SchemaCard lookup.
        return new ViewManifest(name, scope.strip(), table, title, columns);
    }
}
