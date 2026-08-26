package io.lumnik.hub.validator.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

public final class TableDefaultsMerger {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TableDefaultsMerger() {}

    public static JsonNode merge(JsonNode defaults, JsonNode table) {
        ObjectNode merged = MAPPER.createObjectNode();
        if (defaults != null && defaults.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = defaults.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                merged.set(e.getKey(), e.getValue());
            }
        }
        if (table != null && table.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = table.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                merged.set(e.getKey(), e.getValue());
            }
        }
        return merged;
    }
}
