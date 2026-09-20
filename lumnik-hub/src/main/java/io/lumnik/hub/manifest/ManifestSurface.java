package io.lumnik.hub.manifest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The parser scaffold shared by the four app-ladder manifest kinds (View/App/Process/Workflow):
 * one {@link YAMLMapper}, the {@code honoredSurface()} formatter, and the
 * {@code unknownKeys(JsonNode)} delegation to {@link ManifestKeys}. Each parser declares its own
 * groups, in the same order its {@code ALLOWED_BY_PATH} map declared them — the walk path
 * navigates ({@link ManifestKeys#unknownKeys}), the label is what a refusal PRINTS, and the two
 * are not always the same string: an array group's label drops the {@code spec.} prefix its walk
 * path needs (see {@code WorkflowManifestParser}'s {@code spec.states[]} -> {@code "states[]"}).
 */
public final class ManifestSurface {

    private static final YAMLMapper MAPPER = new YAMLMapper();

    private final List<Group> groups;
    private final Map<String, Set<String>> allowedByPath;

    private record Group(String walkPath, String label, Set<String> keys) {}

    private ManifestSurface(List<Group> groups) {
        this.groups = List.copyOf(groups);
        // A LinkedHashMap, wrapped (not copied by Map.copyOf, which does not preserve order) —
        // ManifestKeys.unknownKeys iterates this in insertion order so a manifest's unknown keys
        // are reported top-to-bottom, matching the document's own shape.
        Map<String, Set<String>> byPath = new LinkedHashMap<>();
        for (Group g : groups) byPath.put(g.walkPath(), g.keys());
        this.allowedByPath = Collections.unmodifiableMap(byPath);
    }

    public static Builder builder() {
        return new Builder();
    }

    public JsonNode readTree(String yaml) throws JsonProcessingException {
        return MAPPER.readTree(yaml);
    }

    public List<String> unknownKeys(JsonNode root) {
        return ManifestKeys.unknownKeys(root, allowedByPath);
    }

    public String honoredSurface() {
        return groups.stream()
            .map(g -> g.label() + " {" + String.join(", ", new TreeSet<>(g.keys())) + "}")
            .collect(Collectors.joining(", "));
    }

    public static final class Builder {
        private final List<Group> groups = new ArrayList<>();

        private Builder() {}

        public Builder group(String walkPath, String label, Set<String> keys) {
            groups.add(new Group(walkPath, label, keys));
            return this;
        }

        public ManifestSurface build() {
            return new ManifestSurface(groups);
        }
    }
}
