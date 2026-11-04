package io.lumnik.hub.manifest;

import com.fasterxml.jackson.databind.JsonNode;

import io.lumnik.common.security.ScopeGrants;
import io.lumnik.common.text.Text;
import java.util.ArrayList;
import java.util.HashSet;
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

    /**
     * The column names a manifest lists at {@code path}: a Process's {@code spec.emit.fields} (#1365), a workflow's
     * {@code spec.dormancy.fields} and {@code spec.due.fields} (#1588). Distinct and non-blank; empty when absent.
     */
    public static List<String> columnNames(JsonNode node, String path) {
        if (node.isMissingNode() || node.isNull()) return List.of();
        if (!node.isArray())
            throw new IllegalArgumentException(path + " must be a list of column names");
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode f : node) {
            String name = f.isTextual() ? f.asText() : null;
            if (Text.blank(name))
                throw new IllegalArgumentException(path + " holds a blank or non-text entry");
            if (!seen.add(name))
                throw new IllegalArgumentException(path + " names " + name + " twice");
            out.add(name);
        }
        return List.copyOf(out);
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
        if (Text.blank(value))
            throw new IllegalArgumentException(path + " is required");
        return value;
    }

    /**
     * The scope tag a manifest declares, from the text as written: stripped, then checked against
     * the one rule every declarer shares ({@link ScopeGrants#nameProblem}, #577). The tag becomes the
     * case-sensitive Keycloak role {@code scope:<tag>}, and accidental whitespace must not leak into
     * authorization (a bound user would get a lying 403) — so it is stripped before it is judged.
     * The caller reads {@code written} with the strictness its kind promises.
     */
    public static String scopeTag(String written) {
        String tag = written.strip();
        ScopeGrants.nameProblem(tag)
            .ifPresent(problem -> { throw new IllegalArgumentException("spec.scope: " + problem); });
        return tag;
    }

    /**
     * {@link #requiredText}, strict: a present node that is not a string ({@code name: 123},
     * {@code mode: [snapshot]}) is refused instead of being coerced through Jackson's {@code asText()}.
     * For the kinds that promise strict validation — kind:Export and kind:SearchIndex.
     */
    public static String strictRequiredText(JsonNode node, String field, String path) {
        JsonNode v = node.path(field);
        if (!v.isMissingNode() && !v.isNull() && !v.isTextual())
            throw new IllegalArgumentException(path + " must be a string");
        return requiredText(node, field, path);
    }

    /** {@link #strictRequiredText}, but {@code null} when the field is genuinely absent. */
    public static String strictText(JsonNode node, String field, String path) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        if (!v.isTextual())
            throw new IllegalArgumentException(path + " must be a string");
        return v.asText();
    }
}
