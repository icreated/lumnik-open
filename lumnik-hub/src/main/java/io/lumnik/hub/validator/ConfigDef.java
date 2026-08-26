package io.lumnik.hub.validator;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.function.Function;

public final class ConfigDef {

    public enum Type { STRING, INT, LONG, BOOLEAN, LIST, NESTED }
    public enum Importance { HIGH, MEDIUM, LOW }

    private final LinkedHashMap<String, FieldSpec> fields = new LinkedHashMap<>();

    public record FieldSpec(
        String name,
        Type type,
        Importance importance,
        String doc,
        Object defaultValue,
        boolean required,
        Function<JsonNode, Optional<String>> validator,
        RequiredWhen requiredWhen,
        List<String> recommendations,
        ConfigDef nested
    ) {}

    public record RequiredWhen(String otherFieldPath, String otherFieldValue) {}

    public ConfigDef require(String name, Type type, Importance imp, String doc) {
        fields.put(name, new FieldSpec(name, type, imp, doc, null, true, null, null, List.of(), null));
        return this;
    }

    public ConfigDef optional(String name, Type type, Importance imp, String doc, Object defaultValue) {
        fields.put(name, new FieldSpec(name, type, imp, doc, defaultValue, false, null, null, List.of(), null));
        return this;
    }

    public ConfigDef requireEnum(String name, Importance imp, String doc, String... allowed) {
        Function<JsonNode, Optional<String>> v = node -> {
            String s = node.asText();
            return Arrays.asList(allowed).contains(s)
                ? Optional.empty()
                : Optional.of("must be one of: " + String.join(", ", allowed));
        };
        fields.put(name, new FieldSpec(name, Type.STRING, imp, doc, null, true, v, null,
            Arrays.asList(allowed), null));
        return this;
    }

    public ConfigDef requireNested(String name, Importance imp, String doc, ConfigDef sub) {
        fields.put(name, new FieldSpec(name, Type.NESTED, imp, doc, null, true, null, null, List.of(), sub));
        return this;
    }

    public ConfigDef requireWhen(String name, Type type, Importance imp, String doc,
                                  String otherFieldPath, String otherFieldValue) {
        fields.put(name, new FieldSpec(name, type, imp, doc, null, false, null,
            new RequiredWhen(otherFieldPath, otherFieldValue), List.of(), null));
        return this;
    }

    public ConfigDef requireCustom(String name, Type type, Importance imp, String doc,
                                    Function<JsonNode, Optional<String>> validator) {
        fields.put(name, new FieldSpec(name, type, imp, doc, null, true, validator, null, List.of(), null));
        return this;
    }

    public Collection<FieldSpec> fields() { return fields.values(); }

    private boolean closed;

    /**
     * Marks this block's key set as COMPLETE: the runner warns on any key not declared
     * here (C3, closed-blocks-first — only call this once every runtime-accepted key is
     * declared, or the warning lies).
     */
    public ConfigDef closed() { this.closed = true; return this; }
    public boolean isClosed() { return closed; }
    public boolean declares(String name) { return fields.containsKey(name); }
}
