package io.lumnik.hub.search;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.entity.EntityManifestParser;
import io.lumnik.hub.manifest.LinkTemplate;
import io.lumnik.hub.manifest.ManifestKeys;
import io.lumnik.hub.manifest.ManifestSurface;
import io.lumnik.hub.search.SearchIndexManifest.Field;
import io.lumnik.hub.search.SearchIndexManifest.Link;
import io.lumnik.hub.search.SearchIndexManifest.Match;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Parses a {@code kind: SearchIndex} manifest (#669). Structural validation only, strict like
 * kind:Export: the cross-check against the referenced Entity's canonical columns and scopes lives at
 * the apply door, against the entity's stored definition.
 */
public class SearchIndexManifestParser {

    static final String API_VERSION = "search.lumnik.io/v1";

    private static final String METADATA = "metadata";
    private static final Set<String> ROOT_KEYS = Set.of("apiVersion", "kind", METADATA, "spec");
    private static final Set<String> METADATA_KEYS = Set.of("name");
    private static final Set<String> SPEC_KEYS = Set.of("entity", "language", "titleField", "fields", "links");
    private static final Set<String> FIELD_KEYS = Set.of("name", "match", "facet");
    private static final Set<String> LINK_KEYS = Set.of("field", "url");
    static final int MAX_LINKS = 16;

    private static final ManifestSurface SURFACE = ManifestSurface.builder()
        .group("", "root", ROOT_KEYS)
        .group(METADATA, "metadata", METADATA_KEYS)
        .group("spec", "spec", SPEC_KEYS)
        .group("spec.fields[]", "spec.fields[]", FIELD_KEYS)
        .group("spec.links[]", "spec.links[]", LINK_KEYS)
        .build();

    /** Names that are hub bookkeeping, never business data: refused even if an entity exposed them.
     *  {@code EntityManifestParser.RESERVED} plus {@code tenant_id} (a column no Entity manifest can
     *  itself declare, so it never needs a place in that blocklist) — one shared source of truth
     *  instead of two independently hand-copied literals, which had already drifted apart once
     *  (code review, #671). */
    private static final Set<String> INTERNAL_COLUMNS = Stream.concat(
            EntityManifestParser.RESERVED.stream(), Stream.of("tenant_id"))
        .collect(Collectors.toUnmodifiableSet());

    /**
     * The languages every candidate provider analyzes natively (PostgreSQL text search configurations
     * and OpenSearch/Elasticsearch language analyzers share these), so a manifest never names one a
     * provider would have to fake.
     */
    static final Set<String> LANGUAGES =
        Set.of("simple", "english", "french", "german", "italian", "spanish", "dutch", "portuguese");

    public List<String> unknownKeys(JsonNode root) {
        return SURFACE.unknownKeys(root);
    }

    public static String honoredSurface() {
        return SURFACE.honoredSurface();
    }

    /**
     * The two ways to widen who sees the data — declaring scopes, or pointing at a raw table — refused
     * BY NAME. Must run before {@link #unknownKeys}: neither key is in the honored surface, so the
     * generic refusal would otherwise reach them first and the integrator would read "unknown key"
     * instead of why (the {@code ExportManifestParser.refuseInlineCredentials} ordering).
     */
    public Optional<String> namedRefusal(JsonNode root) {
        JsonNode spec = root.path("spec");
        for (String key : List.of("scope", "scopes"))
            if (spec.has(key))
                return Optional.of("spec." + key
                    + ": scopes are inherited from the entity and cannot be declared on a SearchIndex");
        for (String key : List.of("table", "source"))
            if (spec.has(key))
                return Optional.of("spec." + key
                    + ": a SearchIndex indexes a kind: Entity (spec.entity), never a raw table");
        return Optional.empty();
    }

    /** The document root, refused unless it is a YAML mapping (same guard as kind:Export). */
    public JsonNode readTree(String yaml) {
        JsonNode root;
        try {
            root = SURFACE.readTree(yaml);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid kind:SearchIndex manifest: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject())
            throw new IllegalArgumentException(
                "invalid kind:SearchIndex manifest: expected a YAML mapping (object) at the document root");
        return root;
    }

    public SearchIndexManifest parse(String yaml) {
        return parse(readTree(yaml));
    }

    public SearchIndexManifest parse(JsonNode root) {
        if (!API_VERSION.equals(requireText(root, "apiVersion", "apiVersion")))
            throw new IllegalArgumentException("apiVersion must be '" + API_VERSION + "'");
        if (!"SearchIndex".equals(requireText(root, "kind", "kind")))
            throw new IllegalArgumentException("kind must be 'SearchIndex'");

        String name = requireText(root.path(METADATA), "name", "metadata.name");
        JsonNode spec = root.path("spec");

        namedRefusal(root).ifPresent(problem -> { throw new IllegalArgumentException(problem); });

        String entity = requireText(spec, "entity", "spec.entity");

        String language = optionalText(spec, "language", "spec.language");
        if (language == null) language = "simple";
        if (!LANGUAGES.contains(language))
            throw new IllegalArgumentException("spec.language must be one of " + sorted(LANGUAGES) + ": '" + language + "'");

        List<Field> fields = parseFields(spec.path("fields"));
        if (fields.stream().noneMatch(f -> f.match() != null))
            throw new IllegalArgumentException("spec.fields: at least one field must be searchable (declare a match)");

        return new SearchIndexManifest(name, entity, language, fields, parseTitleField(spec, fields),
            parseLinks(spec.path("links"), fields));
    }

    /** Optional; each link sits on a declared field and its URL follows the one external-link template rules. */
    private static List<Link> parseLinks(JsonNode node, List<Field> fields) {
        if (node.isMissingNode() || node.isNull()) return List.of();
        if (!node.isArray()) throw new IllegalArgumentException("spec.links must be a list");
        if (node.size() > MAX_LINKS)
            throw new IllegalArgumentException("spec.links: at most " + MAX_LINKS + " links");
        List<String> names = fields.stream().map(Field::name).toList();
        List<Link> links = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int i = 0;
        for (JsonNode l : node) {
            String path = "spec.links[" + (i++) + "]";
            if (!l.isObject()) throw new IllegalArgumentException(path + " must be a mapping");
            String field = requireText(l, "field", path + ".field");
            if (!names.contains(field))
                throw new IllegalArgumentException(path + ".field: '" + field
                    + "' is not one of spec.fields — a link sits on a field the hit returns");
            if (!seen.add(field))
                throw new IllegalArgumentException(path + ".field: '" + field + "' already carries a link");
            String raw = requireText(l, "url", path + ".url");
            links.add(new Link(field, LinkTemplate.externalUrl(raw, path + ".url", names, "spec.fields")));
        }
        return links;
    }

    /** Optional; when given it names one declared field. Case is kept: field names are canonical columns. */
    private static String parseTitleField(JsonNode spec, List<Field> fields) {
        JsonNode node = spec.path("titleField");
        if (node.isMissingNode() || node.isNull()) return null;
        String title = ManifestKeys.strictText(spec, "titleField", "spec.titleField");
        if (title == null || title.isBlank())
            throw new IllegalArgumentException("spec.titleField must name one of spec.fields");
        if (fields.stream().noneMatch(f -> f.name().equals(title)))
            throw new IllegalArgumentException("spec.titleField: '" + title
                + "' is not one of spec.fields — name a declared field");
        return title;
    }

    private static List<Field> parseFields(JsonNode node) {
        if (!node.isArray() || node.isEmpty())
            throw new IllegalArgumentException("spec.fields must declare at least one field");
        List<Field> fields = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int i = 0;
        for (JsonNode f : node) {
            String path = "spec.fields[" + (i++) + "]";
            if (!f.isObject()) throw new IllegalArgumentException(path + " must be a mapping");
            String name = requireText(f, "name", path + ".name");
            if (INTERNAL_COLUMNS.contains(name))
                throw new IllegalArgumentException(path + ".name: '" + name + "' is an internal column and cannot be indexed");
            if (!seen.add(name))
                throw new IllegalArgumentException(path + ".name: duplicate field '" + name + "'");
            fields.add(new Field(name, parseMatch(f, path), parseFacet(f, path)));
        }
        return fields;
    }

    private static Match parseMatch(JsonNode f, String path) {
        String raw = optionalText(f, "match", path + ".match");
        if (raw == null) return null;
        if ("phonetic".equals(raw))
            throw new IllegalArgumentException(path + ".match: 'phonetic' is not supported yet");
        return switch (raw) {
            case "exact" -> Match.EXACT;
            case "text" -> Match.TEXT;
            case "fuzzy" -> Match.FUZZY;
            case "prefix" -> Match.PREFIX;
            default -> throw new IllegalArgumentException(
                path + ".match must be one of exact, text, fuzzy, prefix: '" + raw + "'");
        };
    }

    private static boolean parseFacet(JsonNode f, String path) {
        JsonNode v = f.path("facet");
        if (v.isMissingNode() || v.isNull()) return false;
        if (!v.isBoolean()) throw new IllegalArgumentException(path + ".facet must be a boolean");
        return v.asBoolean();
    }

    private static String requireText(JsonNode node, String field, String path) {
        return ManifestKeys.strictRequiredText(node, field, path);
    }

    /** Strict, then normalized: optional enum-like values are compared stripped and lower-cased. */
    private static String optionalText(JsonNode node, String field, String path) {
        String v = ManifestKeys.strictText(node, field, path);
        return v == null ? null : v.strip().toLowerCase(Locale.ROOT);
    }

    private static List<String> sorted(Set<String> s) {
        return s.stream().sorted().toList();
    }
}
