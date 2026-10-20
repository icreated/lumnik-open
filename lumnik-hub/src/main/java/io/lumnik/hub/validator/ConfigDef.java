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

    /**
     * An optional field whose TYPE refusal carries a hint. {@code requireEnum} could always word
     * its own refusal; {@code optional} could not, so a block that wanted to teach the shape of
     * its value had to repeat the type check by hand in a cross-field rule — and then one mistake
     * came back as two errors ({@code spec.headers}, {@code spec.target.indexes}). The
     * declaration owns the shape; this is how it keeps the sentence that made the refusal useful.
     */
    public ConfigDef optionalHinted(String name, Type type, Importance imp, String doc,
                                     Object defaultValue, String hint) {
        // Fail fast and say what to do instead: a null hint is not a hintless hinted field, it is
        // a call that meant optional(). Without this, List.of(null) throws from inside the JDK at
        // the first validate request — an NPE whose stack trace names neither the key nor this
        // method. Coercing null to no recommendations would be the wrong mercy: it would ship a
        // refusal that teaches nothing, which is the silence this whole key-set work removes.
        Objects.requireNonNull(hint, () -> "optionalHinted(\"" + name + "\") needs a hint — "
            + "call optional() when the type refusal needs no sentence of its own");
        fields.put(name, new FieldSpec(name, type, imp, doc, defaultValue, false, null, null,
            List.of(hint), null));
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

    /**
     * A block that must be present, with its key set attached. Before reaching for it, check that
     * {@code sub} has a required field — or that a cross-field rule owns its requiredness, the way
     * {@code KafkaConnectorValidator.validateAuth} does for an all-optional auth def. A sub-def with
     * neither makes a block that can only be written to say nothing, and every manifest of the kind
     * then carries a line the runtime reads nothing from: that was {@code spec.target} on jdbc and
     * csv, and {@code spec.parser} on csv, until #543. {@link #optionalNested} is the declaration
     * those wanted.
     */
    public ConfigDef requireNested(String name, Importance imp, String doc, ConfigDef sub) {
        fields.put(name, new FieldSpec(name, Type.NESTED, imp, doc, null, true, null, null, List.of(), sub));
        return this;
    }

    /**
     * A block that may be omitted, with its key set attached. Without this, an optional block can
     * only be declared as {@code optional(name, Type.NESTED, …)} — which leaves {@code nested} null,
     * so {@link ConfigDefRunner}'s recursion (and the unknown-key sweep with it) is unreachable for
     * the whole block, and the sweep has to be called by hand from a cross-field rule instead.
     */
    public ConfigDef optionalNested(String name, Importance imp, String doc, ConfigDef sub) {
        fields.put(name, new FieldSpec(name, Type.NESTED, imp, doc, null, false, null, null, List.of(), sub));
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

    public ConfigDef optionalCustom(String name, Type type, Importance imp, String doc,
                                     Function<JsonNode, Optional<String>> validator) {
        fields.put(name, new FieldSpec(name, type, imp, doc, null, false, validator, null, List.of(), null));
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
