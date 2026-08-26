package io.lumnik.hub.validator;

import com.fasterxml.jackson.databind.JsonNode;

/** Null/blank helpers for optional config fields read as Jackson nodes (the NullNode.asText()=="null" trap). */
public final class JsonNodes {
    private JsonNodes() {}

    /** True iff the node is present, textual, and not blank. */
    public static boolean nonBlank(JsonNode n) {
        return n != null && n.isTextual() && !n.asText().isBlank();
    }

    /**
     * True iff the node is absent (null ref / MissingNode / NullNode) or its text value is blank.
     * Non-textual scalar nodes (e.g. number/boolean) are treated as present (not blank).
     * NullNode.asText() is the literal "null" (non-blank), so the isNull() guard is required.
     */
    public static boolean blankOrNull(JsonNode v) {
        return v == null || v.isMissingNode() || v.isNull() || v.asText().isBlank();
    }

    /** The node's text when present and non-blank, else null — for optional string spec fields.
     *  Like its siblings, a null reference (node.get() on an absent field) means absent. */
    public static String textOrNull(JsonNode node) {
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }
}
