package io.lumnik.hub.app;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.hub.manifest.ManifestKeys;
import io.lumnik.hub.manifest.ManifestSurface;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Parses a kind:App YAML manifest into an {@link AppManifest}. Structural, plus the two checks that
 * need nothing but the manifest itself: the icon is one of the curated identifiers, and the accent
 * colour is legible under the chat bubble's fixed foreground. The scope tag is checked too, against
 * the one rule every declarer shares.
 */
public class AppManifestParser {

    /**
     * The curated set: one identifier per icon lumnik provides, and nothing else can be named —
     * no upload, no arbitrary URL. The full enum is accepted here on purpose, ahead of the PNG
     * pairs for {@code generic}/{@code event}/{@code alert}/{@code contract}, which arrive with
     * the consumption half of this feature; that way the shell gains its images without a second
     * migration and without widening what an apply accepts.
     */
    public static final Set<String> ICONS =
        Set.of("clients", "stock", "generic", "event", "alert", "contract");

    /** Today's {@code --accent} / {@code --bg} (styles.css :root): what a themeless app wears. */
    static final String DEFAULT_ACCENT = "#8b7ff0";
    static final String DEFAULT_BACKGROUND = "#14121f";

    private static final Pattern HEX_COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

    // The closed inventory (C3), kept beside parse() on purpose: a field read there without a key
    // declared here is the drift that makes the check cry wolf, and the tests pin both halves.
    // apiVersion is DECLARED though parse() ignores it — every app-ladder manifest carries the
    // apps.lumnik.io/v1 envelope, so refusing it would refuse the documented shape.
    private static final String METADATA = "metadata";
    private static final String SPEC = "spec";
    private static final String THEME = "spec.theme";

    private static final Set<String> ROOT_KEYS = Set.of("apiVersion", "kind", METADATA, SPEC);
    private static final Set<String> METADATA_KEYS = Set.of("name");
    private static final Set<String> SPEC_KEYS = Set.of("title", "scope", "icon", "theme", "default");
    private static final Set<String> THEME_KEYS = Set.of("accentColor", "backgroundColor");

    private static final ManifestSurface SURFACE = ManifestSurface.builder()
        .group("", "root", ROOT_KEYS)
        .group(METADATA, "metadata", METADATA_KEYS)
        .group(SPEC, "spec", SPEC_KEYS)
        .group(THEME, "spec.theme", THEME_KEYS)
        .build();

    /**
     * Every key the manifest declares that this parser does not honour, as dotted paths in document
     * order — empty when it is clean, and empty when it does not parse at all (that is
     * {@link #parse}'s error to raise). Reporting only; the apply door is what refuses.
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

    /** The honoured surface, DERIVED from the inventory above so a refusal cannot contradict it. */
    public static String honoredSurface() {
        return SURFACE.honoredSurface();
    }

    /**
     * Parses once, catching broadly (SnakeYAML/Jackson raise a mix of checked and unchecked
     * failures for a null or malformed body — the existing message must fire for both; an empty
     * or blank-only body does not throw here at all, {@link #parse(JsonNode)} refuses it with its
     * ordinary "kind must be 'App'" check instead). Package-visible so the door can call it once
     * and reuse the node for both parse() and unknownKeys().
     */
    JsonNode readTree(String yaml) {
        try {
            return SURFACE.readTree(yaml);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid kind:App manifest: " + e.getMessage(), e);
        }
    }

    public AppManifest parse(String yaml) {
        return parse(readTree(yaml));
    }

    public AppManifest parse(JsonNode root) {
        if (!"App".equals(ManifestKeys.text(root, "kind")))
            throw new IllegalArgumentException("kind must be 'App'");

        JsonNode meta = root.path(METADATA);
        JsonNode spec = root.path(SPEC);
        JsonNode theme = spec.path("theme");

        String name = ManifestKeys.requiredText(meta, "name", "metadata.name");
        String scope = ManifestKeys.requiredText(spec, "scope", "spec.scope");
        String title = ManifestKeys.requiredText(spec, "title", "spec.title");
        String icon = ManifestKeys.requiredText(spec, "icon", "spec.icon");

        if (!ICONS.contains(icon))
            throw new IllegalArgumentException("spec.icon must be one of " + new TreeSet<>(ICONS)
                + " — the set is curated, and uploads are not accepted");

        String accent = hexOrDefault(theme, "accentColor", DEFAULT_ACCENT);
        double contrast = WcagContrast.ratio(accent, WcagContrast.BUBBLE_TEXT);
        if (contrast < WcagContrast.AA_NORMAL_TEXT)
            throw new IllegalArgumentException(
                "spec.theme.accentColor does not have enough contrast against the chat bubble text "
                    + "colour (" + WcagContrast.BUBBLE_TEXT + ") — WCAG AA needs at least "
                    + WcagContrast.AA_NORMAL_TEXT + ":1, this is "
                    + String.format("%.2f", contrast) + ":1");

        // scope is normalized at the threshold: accidental whitespace must not leak into
        // authorization (a bound user would get a lying 403) or into the registry the PWA reads.
        String tag = scope.strip();

        // …and the normalized tag still has to be a NAME a scope can have. It becomes the
        // case-sensitive Keycloak role `scope:<tag>`, so `CRM` declares an app nobody can ever be
        // granted: visible to unscoped users, permanently invisible to every bound one — and with
        // `default: true`, a bare /app/ landing on it. ScopesValidator (Connector) and
        // EntityManifestParser ask the same question of the same rule; kind:App has a stronger
        // reason than either, because it alone carries the default flag.
        ScopeGrants.nameProblem(tag)
            .ifPresent(problem -> { throw new IllegalArgumentException("spec.scope: " + problem); });

        return new AppManifest(name, tag, title, icon, accent,
            hexOrDefault(theme, "backgroundColor", DEFAULT_BACKGROUND),
            spec.path("default").asBoolean(false));
    }

    private static String hexOrDefault(JsonNode theme, String field, String fallback) {
        String raw = ManifestKeys.text(theme, field);
        if (raw == null || raw.isBlank()) return fallback;
        String hex = raw.strip();
        if (!HEX_COLOR.matcher(hex).matches())
            throw new IllegalArgumentException("spec.theme." + field
                + " must be a 6-digit hex colour like #4fd1a5, got '" + raw + "'");
        return hex;
    }
}
