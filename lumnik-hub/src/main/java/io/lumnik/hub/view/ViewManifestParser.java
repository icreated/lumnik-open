package io.lumnik.hub.view;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.hub.manifest.ManifestKeys;
import io.lumnik.hub.manifest.ManifestSurface;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Parses a kind:View YAML manifest into a ViewManifest. Structural only — the ViewResolver validates columns. */
public class ViewManifestParser {

    // The closed inventory (C3). Kept beside parse() on purpose: a field added there without a key
    // added here is the drift that would make this check cry wolf, and the tests pin both halves.
    // apiVersion is DECLARED though parse() ignores it: every shipped manifest carries the
    // apps.lumnik.io/v1 envelope (docs/demo/wow-customer/*.yaml among them), so refusing it
    // would cry wolf on the WOW demo itself — the very failure mode the C3 verdict named.
    private static final String METADATA = "metadata";

    private static final Set<String> ROOT_KEYS = Set.of("apiVersion", "kind", METADATA, "spec");
    private static final Set<String> METADATA_KEYS = Set.of("name");
    private static final Set<String> SPEC_KEYS = Set.of("scope", "table", "title", "columns");

    private static final ManifestSurface SURFACE = ManifestSurface.builder()
        .group("", "root", ROOT_KEYS)
        .group(METADATA, "metadata", METADATA_KEYS)
        .group("spec", "spec", SPEC_KEYS)
        .build();

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
            root = SURFACE.readTree(yaml);
        } catch (Exception e) {
            return List.of();
        }
        return unknownKeys(root);
    }

    public List<String> unknownKeys(JsonNode root) {
        return SURFACE.unknownKeys(root);
    }

    /**
     * The honored surface, for a refusal that teaches — <b>derived</b> from the inventory above, so
     * the message can never contradict what is actually accepted. Hand-typed, it drifts on the first
     * edit, and the first casualty is {@code apiVersion}: accepted, unused, and easy to leave out of
     * a sentence.
     */
    public static String honoredSurface() {
        return SURFACE.honoredSurface();
    }

    /**
     * Parses once, catching broadly (SnakeYAML/Jackson raise a mix of checked and unchecked
     * failures for a null or malformed body — the existing message must fire for both; an empty
     * or blank-only body does not throw here at all, {@link #parse(JsonNode)} refuses it with its
     * ordinary "kind must be 'View'" check instead). Package-visible so the door can call it once
     * and reuse the node for both parse() and unknownKeys().
     */
    JsonNode readTree(String yaml) {
        try {
            return SURFACE.readTree(yaml);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid kind:View manifest: " + e.getMessage(), e);
        }
    }

    public ViewManifest parse(String yaml) {
        return parse(readTree(yaml));
    }

    public ViewManifest parse(JsonNode root) {
        if (!"View".equals(ManifestKeys.text(root, "kind")))
            throw new IllegalArgumentException("kind must be 'View'");
        JsonNode meta = root.path(METADATA);
        JsonNode spec = root.path("spec");
        String name = ManifestKeys.requiredText(meta, "name", "metadata.name");
        String scope = ManifestKeys.requiredText(spec, "scope", "spec.scope");
        String table = ManifestKeys.text(spec, "table");
        String title = ManifestKeys.text(spec, "title");
        List<String> columns = new ArrayList<>();
        for (JsonNode c : spec.path("columns")) columns.add(c.asText());

        if (columns.isEmpty())
            throw new IllegalArgumentException("spec.columns must declare at least one column");

        // scope is normalized at the threshold: accidental whitespace must not leak into
        // authorization (a bound user would get a lying 403) or the SchemaCard lookup.
        String tag = scope.strip();

        // …and the normalized tag still has to be a NAME a scope can have — the same rule
        // AppManifestParser, ScopesValidator and EntityManifestParser already ask (#577).
        ScopeGrants.nameProblem(tag)
            .ifPresent(problem -> { throw new IllegalArgumentException("spec.scope: " + problem); });

        return new ViewManifest(name, tag, table, title, columns);
    }
}
