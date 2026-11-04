package io.lumnik.hub.validator;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.json.JsonNodes;
import io.lumnik.hub.validator.ConfigDef.AtLeastOneOf;
import io.lumnik.hub.validator.ConfigDef.FieldSpec;
import io.lumnik.hub.validator.ConfigDef.RequiredWhen;
import io.lumnik.hub.validator.ConfigDef.Type;
import io.lumnik.hub.validator.ValidationError.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ConfigDefRunner {

    /** The closed sweep's warning for a key no declaration names. */
    public static final String UNKNOWN_KEY = "unknown key — ignored by the runtime";

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
        for (AtLeastOneOf rule : def.atLeastOneOf()) {
            if (rule.test().test(block) && rule.names().stream().noneMatch(n -> JsonNodes.nonBlank(block.get(n)))) {
                out.add(new ValidationError(pathPrefix == null ? "" : pathPrefix, Severity.ERROR,
                    "at least one of " + String.join(", ", rule.names()) + " is required when " + rule.text(),
                    List.of(rule.hint())));
            }
        }
        if (def.isClosed()) {
            block.fieldNames().forEachRemaining(k -> {
                if (!def.declares(k)) {
                    String full = (pathPrefix == null || pathPrefix.isEmpty()) ? k : pathPrefix + "." + k;
                    out.add(new ValidationError(full, Severity.WARNING,
                        UNKNOWN_KEY,
                        List.of("Remove it, or check the spelling")));
                }
            });
        }
        return out;
    }

    private static void validateField(FieldSpec f, JsonNode block, String full, List<ValidationError> out) {
        JsonNode value = block.get(f.name());
        if (f.refusal() != null) {
            if (!JsonNodes.absent(value)) {
                out.add(new ValidationError(full, Severity.ERROR, f.refusal(), f.recommendations()));
            }
            return;
        }
        boolean conditional = f.requiredWhen() != null && f.requiredWhen().test().test(block);
        // Where a field is required by a condition, "nothing" has three spellings — the key missing, `key:`
        // with nothing after it, and a blank string or an empty list — and none of them gives the field a value.
        boolean present = value != null && !value.isNull() && !(conditional && isEmptyValue(value));

        if (!present) {
            boolean shouldBePresent = f.required() || conditional;
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

        if (f.gate() != null && !f.gate().test().test(block)) {
            out.add(new ValidationError(full, Severity.WARNING,
                f.name() + " is ignored unless " + f.gate().text() + " — the runtime reads it only then",
                List.of("Remove it, or set " + f.gate().setTo())));
            return;
        }

        // The advice speaks of a value that is valid: one the field refused gets the refusal and nothing beside it.
        boolean refused = false;
        if (f.validator() != null) {
            Optional<String> refusal = f.validator().apply(value);
            refusal.ifPresent(s -> out.add(new ValidationError(full, Severity.ERROR, s, f.recommendations())));
            refused = refusal.isPresent();
        }

        if (!refused && f.advice() != null && f.advice().test().test(block) && !isEmptyValue(value)) {
            out.add(new ValidationError(full, Severity.WARNING, f.advice().message(), f.advice().hints()));
        }

        if (f.nested() != null && f.nested().isDescribesOnly()) return;   // published, not enforced from here
        if ((f.type() == Type.NESTED || f.type() == Type.STRING_OR_NESTED) && value.isObject() && f.nested() != null) {
            out.addAll(run(f.nested(), value, full));
        }
        if (f.type() == Type.LIST && f.nested() != null && value.isArray()) {
            for (int i = 0; i < value.size(); i++) {
                JsonNode element = value.get(i);
                String at = full + "[" + i + "]";
                if (element.isObject()) {
                    out.addAll(run(f.nested(), element, at));
                } else {
                    out.add(new ValidationError(at, Severity.ERROR,
                        "type mismatch: expected object, got " + element.getNodeType(), f.recommendations()));
                }
            }
        }
    }

    private static boolean isEmptyValue(JsonNode value) {
        return (value.isTextual() && value.asText().isBlank()) || (value.isArray() && value.isEmpty());
    }

    private static String describeWhen(RequiredWhen rw) {
        return rw == null ? ""
            : " (required when " + rw.text() + ")";
    }

    private static String checkType(JsonNode v, Type expected) {
        return switch (expected) {
            case STRING  -> v.isTextual()           ? null : "expected string, got "  + v.getNodeType();
            case INT     -> v.isInt()               ? null : "expected int, got "     + v.getNodeType();
            case LONG    -> (v.isInt() || v.isLong()) ? null : "expected long, got " + v.getNodeType();
            case BOOLEAN -> v.isBoolean()           ? null : "expected boolean, got " + v.getNodeType();
            case LIST    -> v.isArray()             ? null : "expected list, got "    + v.getNodeType();
            case NESTED  -> v.isObject()            ? null : "expected object, got "  + v.getNodeType();
            case STRING_OR_NESTED -> v.isTextual() || v.isObject() ? null : "expected string or object, got " + v.getNodeType();
        };
    }
}
