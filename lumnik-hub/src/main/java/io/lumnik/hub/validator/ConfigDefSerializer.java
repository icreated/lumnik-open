package io.lumnik.hub.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.validator.ConfigDef.FieldSpec;

public final class ConfigDefSerializer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ConfigDefSerializer() {}

    public static JsonNode toJson(ConfigDef def) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode fields = root.putArray("fields");
        for (FieldSpec f : def.fields()) {
            ObjectNode node = fields.addObject();
            node.put("name", f.name());
            node.put("type", f.type().name());
            node.put("importance", f.importance().name());
            node.put("required", f.required());
            node.put("doc", f.doc() == null ? "" : f.doc());
            if (f.defaultValue() != null) {
                node.putPOJO("default", f.defaultValue());
            }
            if (f.requiredWhen() != null) {
                ObjectNode rw = node.putObject("required_when");
                rw.put("field", f.requiredWhen().otherFieldPath());
                rw.put("equals", f.requiredWhen().otherFieldValue());
            }
            if (!f.recommendations().isEmpty()) {
                ArrayNode recs = node.putArray("recommendations");
                f.recommendations().forEach(recs::add);
            }
            if (f.nested() != null) {
                node.set("nested", toJson(f.nested()));
            }
        }
        return root;
    }
}
