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
     * True iff the node is absent — a null reference, a MissingNode, or a NullNode.
     *
     * <p>The last of the three is the one that catches people: YAML spells absence two ways, the key
     * missing and {@code parser:} with nothing after it, and only the first is a MissingNode. This is
     * {@code ConfigDefRunner}'s own reading (it takes present as non-null AND non-missing), so a
     * guard that asks {@code isMissingNode()} alone disagrees with the validator that let the
     * manifest through.
     */
    public static boolean absent(JsonNode v) {
        return v == null || v.isMissingNode() || v.isNull();
    }

    /**
     * True iff the node is {@link #absent} or its text value is blank — the predicate for a STRING
     * field. Non-textual scalar nodes (e.g. number/boolean) are treated as present (not blank).
     *
     * <p>Not interchangeable with {@code absent}: this one exists because {@code NullNode.asText()}
     * is the literal {@code "null"} (non-blank), and it must not be used on a BLOCK, because
     * {@code ObjectNode.asText()} is {@code ""} and it would call every present block blank.
     */
    public static boolean blankOrNull(JsonNode v) {
        return absent(v) || v.asText().isBlank();
    }

    /** The node's text when present and non-blank, else null — for optional string spec fields.
     *  Like its siblings, a null reference (node.get() on an absent field) means absent. */
    public static String textOrNull(JsonNode node) {
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }
}
