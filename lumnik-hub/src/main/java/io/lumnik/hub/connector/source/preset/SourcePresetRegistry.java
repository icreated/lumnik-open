package io.lumnik.hub.connector.source.preset;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects {@link SourcePreset} beans (Open/Closed) and expands a vendor preset at the front door:
 * {@code spec.preset: event-hubs} becomes a real {@code kind: kafka} config, so the engine/type never see
 * the vendor alias. Mirrors {@code PaginationPresetRegistry}.
 */
@ApplicationScoped
public class SourcePresetRegistry {

    @Inject Instance<SourcePreset> beans;
    private final Map<String, SourcePreset> byKind = new HashMap<>();

    @PostConstruct
    void index() {
        for (SourcePreset p : beans) {
            if (byKind.putIfAbsent(p.kind(), p) != null) {
                throw new IllegalStateException("Duplicate source preset kind: " + p.kind());
            }
        }
    }

    public boolean isPreset(String kind) { return byKind.containsKey(kind); }

    /** If {@code kind} is a preset, return the expanded real config; else return {@code config} unchanged. */
    public Map<String, Object> expand(String kind, Map<String, Object> config) {
        SourcePreset preset = byKind.get(kind);
        return preset == null ? config : preset.expand(config);
    }

    /**
     * Required input keys that are absent or blank in {@code config} for the named preset — the single
     * source of truth both front doors (validator, apply handler) consult to reject incomplete preset
     * specs <em>before</em> expansion (expansion would otherwise fill blanks with empty strings and
     * produce an unusable config). Empty for a non-preset kind.
     */
    public List<String> missingInputs(String kind, Map<String, Object> config) {
        SourcePreset preset = byKind.get(kind);
        if (preset == null) return List.of();
        List<String> missing = new ArrayList<>();
        for (String key : preset.requiredInputs()) {
            Object v = config == null ? null : config.get(key);
            // Required inputs are substituted into templates (e.g. broker host), so they must be
            // non-blank STRINGS. A non-string value (number, map) is treated as missing — otherwise it
            // would silently produce a broken expanded config with no early error.
            if (!(v instanceof String s) || s.isBlank()) missing.add(key);
        }
        return missing;
    }
}
