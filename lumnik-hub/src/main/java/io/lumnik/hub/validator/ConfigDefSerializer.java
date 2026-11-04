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
        ArrayNode refused = MAPPER.createArrayNode();
        for (FieldSpec f : def.fields()) {
            // A refused key is not a setting: it is published apart, so nobody reads it as one.
            if (f.refusal() != null) {
                ObjectNode r = refused.addObject();
                r.put("name", f.name());
                r.put("message", f.refusal());
                ArrayNode recs = r.putArray("recommendations");
                f.recommendations().forEach(recs::add);
                continue;
            }
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
                if (f.requiredWhen().otherFieldPath() != null && f.requiredWhen().otherFieldValue() != null) {
                    rw.put("field", f.requiredWhen().otherFieldPath());
                    rw.put("equals", f.requiredWhen().otherFieldValue());
                }
                rw.put("when", f.requiredWhen().text());
            }
            if (f.gate() != null) {
                node.putObject("ignored_unless").put("when", f.gate().text());
            }
            // The values a field accepts and the sentences a refusal of it teaches are two things, published apart so
            // no reader has to tell them by how the text looks. An enum's refusal lists its values as hints too; that
            // repeat is not published a second time.
            if (!f.allowed().isEmpty()) {
                ArrayNode allowed = node.putArray("allowed");
                f.allowed().forEach(allowed::add);
            }
            if (!f.recommendations().isEmpty() && !f.recommendations().equals(f.allowed())) {
                ArrayNode recs = node.putArray("recommendations");
                f.recommendations().forEach(recs::add);
            }
            if (f.nested() != null) {
                // A list's element shape is not a block of the field: `items`, so a reader of `nested` never mistakes it for one.
                node.set(f.type() == ConfigDef.Type.LIST ? "items" : "nested", toJson(f.nested()));
            }
        }
        if (!def.atLeastOneOf().isEmpty()) {
            ArrayNode rules = root.putArray("at_least_one_of");
            for (var rule : def.atLeastOneOf()) {
                ObjectNode r = rules.addObject();
                ArrayNode names = r.putArray("fields");
                rule.names().forEach(names::add);
                r.put("when", rule.text());
                r.put("hint", rule.hint());
            }
        }
        if (!refused.isEmpty()) root.set("refused", refused);
        return root;
    }
}
