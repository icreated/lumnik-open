package io.lumnik.hub.validator.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lumnik.hub.validator.JsonNodes;

import java.util.Iterator;
import java.util.Map;

public final class TableDefaultsMerger {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TableDefaultsMerger() {}

    /**
     * Defaults with the table's own fields laid over them — the node BOTH the validator and the
     * apply handler read, so what this drops is dropped from validation and from what runs.
     *
     * <p>A field written with nothing after it is skipped rather than copied (#551). It is a
     * NullNode, which {@code ConfigDefRunner} already reads as absent, so copying it made the
     * validator approve a merged table whose {@code schema} was null — and the apply then stored
     * that null, ERASING the value {@code defaults} declared three lines above in the same
     * manifest. Absent has to mean absent on both sides of a merge, or a default is not a default.
     */
    public static JsonNode merge(JsonNode defaults, JsonNode table) {
        ObjectNode merged = MAPPER.createObjectNode();
        if (defaults != null && defaults.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = defaults.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                if (!JsonNodes.absent(e.getValue())) merged.set(e.getKey(), e.getValue());
            }
        }
        if (table != null && table.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = table.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                if (!JsonNodes.absent(e.getValue())) merged.set(e.getKey(), e.getValue());
            }
        }
        return merged;
    }
}
