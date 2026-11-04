package io.lumnik.hub.connector.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A "plain dotted path" — {@code $.A.B.C} with no JsonPath operators ({@code [ ] * ..}), no
 * whitespace, and no empty segments. The XML readers' force-list ({@link #forceList}) can only walk such a
 * path, and the connector validator rejects anything else up front. Both sides share this one
 * predicate so the front-door rule and the runtime walk can never drift apart.
 */
public final class DottedPath {

    private DottedPath() {}

    /** True when {@code path} is {@code $.}-prefixed with no operators, whitespace, or empty segments. */
    public static boolean isPlain(String path) {
        if (path == null || !path.startsWith("$.")) return false;
        String p = path.substring(2);
        return !p.isBlank() && !p.startsWith(".") && !p.endsWith(".")
            && p.chars().noneMatch(Character::isWhitespace)
            && !p.contains("[") && !p.contains("*") && !p.contains("..");
    }

    /** The segments after the {@code $.} prefix, split on {@code .}. Requires an {@link #isPlain}
     *  path in full (not merely the {@code $.} prefix) — operators, whitespace, or empty segments are
     *  a call-site bug and fail fast rather than yielding a surprising segment array. */
    public static String[] segments(String path) {
        if (!isPlain(path)) {
            throw new IllegalArgumentException("segments() requires a plain dotted path ($.A.B), got: " + path);
        }
        return path.substring(2).split("\\.");
    }

    /** Coerce the node at a plain dotted {@code response_path} (after {@code $.}) to an array. No-op
     *  when the path uses JsonPath operators or is absent in the tree.
     *  <p>Only the TERMINAL segment is coerced: the walk descends through {@link ObjectNode}
     *  intermediates only, so if an intermediate path segment is itself a repeated (array) element
     *  the walk returns early and the force-list is a no-op for that branch (v1 limitation). */
    public static void forceList(ObjectNode root, String responsePath) {
        // Same predicate the connector validator enforces up front (validator==runtime): only a
        // plain dotted path can be walked here — anything else is a deliberate no-op.
        if (!DottedPath.isPlain(responsePath)) return;
        String[] segs = DottedPath.segments(responsePath);
        ObjectNode parent = root;
        for (int i = 0; i < segs.length - 1; i++) {
            JsonNode child = parent.get(segs[i]);
            if (!(child instanceof ObjectNode on)) return; // path absent → no items to coerce
            parent = on;
        }
        String leaf = segs[segs.length - 1];
        JsonNode leafNode = parent.get(leaf);
        // Only a row is coerced: an object. A scalar is no row, and a list of scalars would reach code that reads rows
        // as maps (review of #1758).
        if (leafNode != null && leafNode.isObject()) {
            ArrayNode arr = parent.arrayNode();
            arr.add(leafNode);
            parent.set(leaf, arr);
        }
    }
}
