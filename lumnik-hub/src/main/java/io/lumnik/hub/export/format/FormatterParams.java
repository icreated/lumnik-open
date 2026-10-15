package io.lumnik.hub.export.format;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;
import java.util.TreeSet;

/** The parameter reads every formatter shares — strict like the rest of kind:Export. */
final class FormatterParams {

    private FormatterParams() {}

    static void refuseUnknown(JsonNode params, Set<String> known) {
        params.fieldNames().forEachRemaining(name -> {
            if (!known.contains(name))
                throw new IllegalArgumentException("unknown parameter '" + name + "' — expected one of "
                    + new TreeSet<>(known));
        });
    }

    /** The text value, {@code null} when absent; a present non-text value is refused. */
    static String text(JsonNode params, String name) {
        JsonNode n = params.get(name);
        if (n == null) return null;
        if (!n.isTextual()) throw new IllegalArgumentException(name + " must be text");
        return n.asText();
    }

    /** An integer in [min, max], {@code fallback} when absent. */
    static int integer(JsonNode params, String name, int fallback, int min, int max, String rule) {
        JsonNode n = params.get(name);
        if (n == null) return fallback;
        if (!n.isIntegralNumber() || !n.canConvertToInt() || n.asInt() < min || n.asInt() > max)
            throw new IllegalArgumentException(name + " must be " + rule);
        return n.asInt();
    }
}
