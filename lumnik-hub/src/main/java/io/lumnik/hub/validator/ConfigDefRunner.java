package io.lumnik.hub.validator;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.validator.ConfigDef.FieldSpec;
import io.lumnik.hub.validator.ConfigDef.RequiredWhen;
import io.lumnik.hub.validator.ConfigDef.Type;
import io.lumnik.hub.validator.ValidationError.Severity;

import java.util.ArrayList;
import java.util.List;

public final class ConfigDefRunner {

    private ConfigDefRunner() {}

    public static List<ValidationError> run(ConfigDef def, JsonNode block, String pathPrefix) {
        List<ValidationError> out = new ArrayList<>();
        if (block == null || !block.isObject()) {
            return out;
        }
        for (FieldSpec f : def.fields()) {
            String full = (pathPrefix == null || pathPrefix.isEmpty())
                ? f.name()
                : pathPrefix + "." + f.name();
            validateField(f, block, full, out);
        }
        if (def.isClosed()) {
            block.fieldNames().forEachRemaining(k -> {
                if (!def.declares(k)) {
                    String full = (pathPrefix == null || pathPrefix.isEmpty()) ? k : pathPrefix + "." + k;
                    out.add(new ValidationError(full, Severity.WARNING,
                        "unknown key — ignored by the runtime",
                        List.of("Remove it, or check the spelling")));
                }
            });
        }
        return out;
    }

    private static void validateField(FieldSpec f, JsonNode block, String full, List<ValidationError> out) {
        JsonNode value = block.get(f.name());
        boolean present = value != null && !value.isNull();

        if (!present) {
            boolean shouldBePresent = f.required()
                || (f.requiredWhen() != null && isConditionMet(block, f.requiredWhen()));
            if (shouldBePresent) {
                out.add(new ValidationError(full, Severity.ERROR,
                    "required field missing" + describeWhen(f.requiredWhen()),
                    f.recommendations()));
            }
            return;
        }

        String typeError = checkType(value, f.type());
        if (typeError != null) {
            out.add(new ValidationError(full, Severity.ERROR,
                "type mismatch: " + typeError, f.recommendations()));
            return;
        }

        if (f.validator() != null) {
            f.validator().apply(value).ifPresent(s ->
                out.add(new ValidationError(full, Severity.ERROR, s, f.recommendations())));
        }

        if (f.type() == Type.NESTED && f.nested() != null) {
            out.addAll(run(f.nested(), value, full));
        }
    }

    private static boolean isConditionMet(JsonNode block, RequiredWhen rw) {
        JsonNode other = block.get(rw.otherFieldPath());
        return other != null && rw.otherFieldValue().equals(other.asText());
    }

    private static String describeWhen(RequiredWhen rw) {
        return rw == null ? ""
            : " (required when " + rw.otherFieldPath() + " = " + rw.otherFieldValue() + ")";
    }

    private static String checkType(JsonNode v, Type expected) {
        return switch (expected) {
            case STRING  -> v.isTextual()           ? null : "expected string, got "  + v.getNodeType();
            case INT     -> v.isInt()               ? null : "expected int, got "     + v.getNodeType();
            case LONG    -> (v.isInt() || v.isLong()) ? null : "expected long, got " + v.getNodeType();
            case BOOLEAN -> v.isBoolean()           ? null : "expected boolean, got " + v.getNodeType();
            case LIST    -> v.isArray()             ? null : "expected list, got "    + v.getNodeType();
            case NESTED  -> v.isObject()            ? null : "expected object, got "  + v.getNodeType();
        };
    }
}
