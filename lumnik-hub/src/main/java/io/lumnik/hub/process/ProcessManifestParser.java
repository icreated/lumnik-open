package io.lumnik.hub.process;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.hub.manifest.ManifestKeys;
import io.lumnik.hub.manifest.ManifestSurface;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses a kind:Process YAML manifest — structural refusals only; the condition honesty
 * (columns/domains) is ProcessConditionResolver's job at apply time. The emit type must stay
 * inside the decision.* taxonomy so the governed event namespace cannot be polluted.
 */
public class ProcessManifestParser {

    private static final String TRIGGER = "data.row.changed";

    // The closed inventory (C3). Kept beside parse() on purpose: a field added there without a key
    // added here is the drift that would make this check cry wolf, and the tests pin both halves.
    // apiVersion is DECLARED though parse() ignores it: every shipped manifest carries the
    // apps.lumnik.io/v1 envelope (docs/demo/wow-customer/*.yaml among them), so refusing it
    // would cry wolf on the WOW demo itself — the very failure mode the C3 verdict named.
    private static final String METADATA = "metadata";

    private static final Set<String> ROOT_KEYS = Set.of("apiVersion", "kind", METADATA, "spec");
    private static final Set<String> METADATA_KEYS = Set.of("name");
    private static final Set<String> SPEC_KEYS = Set.of("scope", "on", "when", "emit");
    private static final Set<String> ON_KEYS = Set.of("event", "table");
    private static final Set<String> EMIT_KEYS = Set.of("type", "message", "fields");

    private static final ManifestSurface SURFACE = ManifestSurface.builder()
        .group("", "root", ROOT_KEYS)
        .group(METADATA, "metadata", METADATA_KEYS)
        .group("spec", "spec", SPEC_KEYS)
        .group("spec.on", "spec.on", ON_KEYS)
        .group("spec.emit", "spec.emit", EMIT_KEYS)
        .build();

    /**
     * Every key the manifest declares that this parser does not honor, as dotted paths in document
     * order — empty when the manifest is clean, and empty when it does not parse at all (that is
     * {@link #parse}'s error to raise, not this check's). Reporting only; the apply door refuses.
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
     * ordinary "kind must be 'Process'" check instead). Package-visible so the door can call it
     * once and reuse the node for both parse() and unknownKeys().
     */
    JsonNode readTree(String yaml) {
        try {
            return SURFACE.readTree(yaml);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid kind:Process manifest: " + e.getMessage(), e);
        }
    }

    public ProcessManifest parse(String yaml) {
        return parse(readTree(yaml));
    }

    public ProcessManifest parse(JsonNode root) {
        if (!"Process".equals(ManifestKeys.text(root, "kind")))
            throw new IllegalArgumentException("kind must be 'Process'");
        JsonNode meta = root.path(METADATA);
        JsonNode spec = root.path("spec");

        // Split from the formerly-fused "metadata.name is required and must be a simple
        // identifier..." sentence — a blank name and a malformed one are different rules and now
        // get different messages (#579's message-drift table).
        String name = ManifestKeys.requiredText(meta, "name", "metadata.name");
        if (!name.matches("[a-z0-9-]+"))
            throw new IllegalArgumentException(
                "metadata.name must be a simple identifier (a-z, 0-9, -): " + name);

        String scope = ManifestKeys.requiredText(spec, "scope", "spec.scope");
        // the tag becomes the case-sensitive Keycloak role scope:<tag> — the same rule
        // AppManifestParser, ViewManifestParser, ScopesValidator and EntityManifestParser ask (#577).
        ScopeGrants.nameProblem(scope)
            .ifPresent(problem -> { throw new IllegalArgumentException("spec.scope: " + problem); });
        JsonNode on = spec.path("on");
        String onEvent = ManifestKeys.text(on, "event");
        if (!TRIGGER.equals(onEvent))
            throw new IllegalArgumentException(
                "spec.on.event must be '" + TRIGGER + "' (the only v1 trigger), got: " + onEvent);
        String onTable = ManifestKeys.text(on, "table");
        if (onTable == null || onTable.isBlank())
            throw new IllegalArgumentException("spec.on.table is required (the raw table whose rows trigger)");
        List<String> when = new ArrayList<>();
        for (JsonNode w : spec.path("when")) when.add(w.asText());
        if (when.isEmpty())
            throw new IllegalArgumentException("spec.when must declare at least one condition");
        JsonNode emit = spec.path("emit");
        String emitType = ManifestKeys.text(emit, "type");
        if (emitType == null || !emitType.startsWith("decision."))
            throw new IllegalArgumentException(
                "spec.emit.type is required and must start with 'decision.' — the governed taxonomy");
        String emitMessage = ManifestKeys.requiredText(emit, "message", "spec.emit.message");
        return new ProcessManifest(name, scope, onEvent, onTable, when, emitType, emitMessage, emitFields(emit));
    }

    /** What an automation reads besides the sentence (#1365): a list of distinct, non-blank column names. */
    private static List<String> emitFields(JsonNode emit) {
        JsonNode fields = emit.path("fields");
        if (fields.isMissingNode() || fields.isNull()) return List.of();
        if (!fields.isArray())
            throw new IllegalArgumentException("spec.emit.fields must be a list of column names");
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode f : fields) {
            String name = f.isTextual() ? f.asText() : null;
            if (name == null || name.isBlank())
                throw new IllegalArgumentException("spec.emit.fields holds a blank or non-text entry");
            if (!seen.add(name))
                throw new IllegalArgumentException("spec.emit.fields names " + name + " twice");
            out.add(name);
        }
        return List.copyOf(out);
    }
}
