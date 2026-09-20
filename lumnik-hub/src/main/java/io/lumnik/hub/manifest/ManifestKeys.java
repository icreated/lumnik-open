package io.lumnik.hub.manifest;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The closed-inventory envelope walk shared by the kind:View, kind:Process and kind:Workflow
 * manifest parsers: given the declared key set for each dotted path in the document, name every
 * key the manifest carries that no path honors. The three parsers differ only in WHICH keys are
 * declared where — that inventory, and every per-kind semantic check, stays in each parser.
 */
public final class ManifestKeys {

    private ManifestKeys() {}

    /**
     * Unknown keys as dotted paths, in the order {@code allowedByPath} is iterated — callers pass
     * a {@link java.util.LinkedHashMap} so that order matches the manifest's own top-to-bottom
     * shape. A path ending in {@code []} names an array: every element is walked in document
     * order and reported as {@code path[i].field}. A path absent from the document (or not an
     * object/array where one is expected) contributes nothing — that is {@code parse()}'s error to
     * raise, not this check's.
     */
    public static List<String> unknownKeys(JsonNode root, Map<String, Set<String>> allowedByPath) {
        if (root == null || !root.isObject()) return List.of();
        List<String> unknown = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : allowedByPath.entrySet()) {
            String path = entry.getKey();
            Set<String> allowed = entry.getValue();
            if (path.endsWith("[]")) {
                String arrayPath = path.substring(0, path.length() - 2);
                JsonNode array = navigate(root, arrayPath);
                if (!array.isArray()) continue; // mistyped node is parse()'s error, not this check's
                int i = 0;
                for (JsonNode element : array) {
                    collect(element, allowed, arrayPath + "[" + (i++) + "].", unknown);
                }
            } else {
                String prefix = path.isEmpty() ? "" : path + ".";
                collect(navigate(root, path), allowed, prefix, unknown);
            }
        }
        return List.copyOf(unknown);
    }

    private static JsonNode navigate(JsonNode root, String path) {
        JsonNode node = root;
        for (String segment : path.split("\\.")) {
            if (segment.isEmpty()) continue;
            node = node.path(segment);
        }
        return node;
    }

    private static void collect(JsonNode node, Set<String> declared, String prefix, List<String> out) {
        if (!node.isObject()) return;
        node.fieldNames().forEachRemaining(f -> {
            if (!declared.contains(f)) out.add(prefix + f);
        });
    }

    /** Reads a scalar text field, {@code null} when absent or explicit null. */
    public static String text(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return n.isMissingNode() || n.isNull() ? null : n.asText();
    }

    /**
     * Reads a scalar text field and refuses it if missing, explicit null, or blank — the one
     * mandatory-field message shape every app-ladder parser should speak, sibling to {@link #text}.
     * Not every "required" check reduces to this: a field whose message also explains WHY, or that
     * carries a second rule fused into the same sentence, stays its own inline check in the parser
     * (see the design doc's "Message drift" section for which ones and why).
     */
    public static String requiredText(JsonNode node, String field, String path) {
        String value = text(node, field);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(path + " is required");
        return value;
    }
}
