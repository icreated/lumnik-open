package io.lumnik.hub.process;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.lumnik.hub.manifest.ManifestKeys;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses a kind:Process YAML manifest — structural refusals only; the condition honesty
 * (columns/domains) is ProcessConditionResolver's job at apply time. The emit type must stay
 * inside the decision.* taxonomy so the governed event namespace cannot be polluted.
 */
public class ProcessManifestParser {

    private static final YAMLMapper MAPPER = new YAMLMapper();
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
    private static final Set<String> EMIT_KEYS = Set.of("type", "message");

    private static final Map<String, Set<String>> ALLOWED_BY_PATH = new LinkedHashMap<>();
    static {
        ALLOWED_BY_PATH.put("", ROOT_KEYS);
        ALLOWED_BY_PATH.put(METADATA, METADATA_KEYS);
        ALLOWED_BY_PATH.put("spec", SPEC_KEYS);
        ALLOWED_BY_PATH.put("spec.on", ON_KEYS);
        ALLOWED_BY_PATH.put("spec.emit", EMIT_KEYS);
    }

    /**
     * Every key the manifest declares that this parser does not honor, as dotted paths in document
     * order — empty when the manifest is clean, and empty when it does not parse at all (that is
     * {@link #parse}'s error to raise, not this check's). Reporting only; the apply door refuses.
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
            + "}, spec {" + sorted(SPEC_KEYS) + "}, spec.on {" + sorted(ON_KEYS)
            + "}, spec.emit {" + sorted(EMIT_KEYS) + "}";
    }

    private static String sorted(Set<String> keys) {
        return String.join(", ", new java.util.TreeSet<>(keys));
    }

    public ProcessManifest parse(String yaml) {
        JsonNode root;
        try {
            root = MAPPER.readTree(yaml);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid kind:Process manifest: " + e.getMessage(), e);
        }
        if (!"Process".equals(ManifestKeys.text(root, "kind")))
            throw new IllegalArgumentException("kind must be 'Process'");
        JsonNode meta = root.path(METADATA);
        JsonNode spec = root.path("spec");
        String name = ManifestKeys.text(meta, "name");
        if (name == null || !name.matches("[a-z0-9-]+"))
            throw new IllegalArgumentException(
                "metadata.name is required and must be a simple identifier (a-z, 0-9, -): " + name);
        String scope = ManifestKeys.text(spec, "scope");
        if (scope == null || scope.isBlank())
            throw new IllegalArgumentException("spec.scope is required");
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
        String emitMessage = ManifestKeys.text(emit, "message");
        if (emitMessage == null || emitMessage.isBlank())
            throw new IllegalArgumentException("spec.emit.message is required");
        return new ProcessManifest(name, scope, onEvent, onTable, when, emitType, emitMessage);
    }
}
