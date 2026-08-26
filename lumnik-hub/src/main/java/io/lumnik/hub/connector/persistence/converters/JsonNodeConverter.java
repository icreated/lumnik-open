package io.lumnik.hub.connector.persistence.converters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class JsonNodeConverter implements AttributeConverter<JsonNode, String> {

    private static final ObjectMapper M = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(JsonNode node) {
        if (node == null) return "null";
        try {
            return M.writeValueAsString(node);
        } catch (Exception e) {
            return "null";
        }
    }

    @Override
    public JsonNode convertToEntityAttribute(String json) {
        if (json == null || json.isBlank()) return NullNode.instance;
        try {
            return M.readTree(json);
        } catch (Exception e) {
            return NullNode.instance;
        }
    }
}
