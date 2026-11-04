package io.lumnik.hub.validator;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

public final class ConfigDef {

    /** {@code STRING_OR_NESTED}: a string as it is, or an object checked by the field's sub-def ({@code unique_key}, #1630). */
    public enum Type { STRING, INT, LONG, BOOLEAN, LIST, NESTED, STRING_OR_NESTED }
    public enum Importance { HIGH, MEDIUM, LOW }

    private final LinkedHashMap<String, FieldSpec> fields = new LinkedHashMap<>();
    private final List<AtLeastOneOf> atLeastOneOf = new ArrayList<>();
    private String last;

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
        ConfigDef nested,
        String refusal,
        Gate gate,
        Advice advice,
        List<String> allowed
    ) {
        /** An optional field with nothing attached: no default, check, condition, hint or sub-def. */
        static FieldSpec of(String name, Type type, Importance importance, String doc) {
            return new FieldSpec(name, type, importance, doc, null, false, null, null, List.of(), null, null, null, null, List.of());
        }

        FieldSpec asRequired() {
            return new FieldSpec(name, type, importance, doc, defaultValue, true, validator, requiredWhen, recommendations, nested, refusal, gate, advice, allowed);
        }

        /** Never required, whatever the field was: its condition goes too. */
        FieldSpec asOptional() {
            return new FieldSpec(name, type, importance, doc, defaultValue, false, validator, null, recommendations, nested, refusal, gate, advice, allowed);
        }

        FieldSpec withDefault(Object defaultValue) {
            return new FieldSpec(name, type, importance, doc, defaultValue, required, validator, requiredWhen, recommendations, nested, refusal, gate, advice, allowed);
        }

        FieldSpec withCheck(Function<JsonNode, Optional<String>> validator) {
            return new FieldSpec(name, type, importance, doc, defaultValue, required, validator, requiredWhen, recommendations, nested, refusal, gate, advice, allowed);
        }

        FieldSpec withRequiredWhen(RequiredWhen requiredWhen) {
            return new FieldSpec(name, type, importance, doc, defaultValue, required, validator, requiredWhen, recommendations, nested, refusal, gate, advice, allowed);
        }

        FieldSpec withHints(List<String> recommendations) {
            return new FieldSpec(name, type, importance, doc, defaultValue, required, validator, requiredWhen, recommendations, nested, refusal, gate, advice, allowed);
        }

        FieldSpec withNested(ConfigDef nested) {
            return new FieldSpec(name, type, importance, doc, defaultValue, required, validator, requiredWhen, recommendations, nested, refusal, gate, advice, allowed);
        }

        /** A key the runtime never reads: any value for it is refused with {@code refusal}, and it is not a setting. */
        FieldSpec withRefusal(String refusal) {
            return new FieldSpec(name, type, importance, doc, defaultValue, required, validator, requiredWhen, recommendations, nested, refusal, gate, advice, allowed);
        }

        FieldSpec withGate(Gate gate) {
            return new FieldSpec(name, type, importance, doc, defaultValue, required, validator, requiredWhen, recommendations, nested, refusal, gate, advice, allowed);
        }

        /** The values the field accepts, apart from the sentences its refusals teach. */
        FieldSpec withAllowed(List<String> allowed) {
            return new FieldSpec(name, type, importance, doc, defaultValue, required, validator, requiredWhen, recommendations, nested, refusal, gate, advice, allowed);
        }

        FieldSpec withAdvice(Advice advice) {
            return new FieldSpec(name, type, importance, doc, defaultValue, required, validator, requiredWhen, recommendations, nested, refusal, gate, advice, allowed);
        }
    }

    /**
     * When a field is required: {@code test} reads the block the field sits in, {@code text} says it in
     * the refusal ("required when …"). {@code otherFieldPath}/{@code otherFieldValue} are set only for
     * the one-sibling-equals-one-value form, which the hub also publishes as {@code field}/{@code equals}.
     */
    public record RequiredWhen(String otherFieldPath, String otherFieldValue, String text, Predicate<JsonNode> test) {
        static RequiredWhen equalTo(String otherFieldPath, String value) {
            return new RequiredWhen(otherFieldPath, value, otherFieldPath + " = " + value, block -> {
                JsonNode other = block.get(otherFieldPath);
                return other != null && value.equals(other.asText());
            });
        }

        static RequiredWhen oneOf(String otherFieldPath, List<String> values) {
            return new RequiredWhen(null, null, otherFieldPath + " is " + String.join(" or ", values),
                block -> block.has(otherFieldPath) && values.contains(block.get(otherFieldPath).asText()));
        }
    }

    /**
     * A key read only where {@code test} holds for its block: given elsewhere it is named as ignored, and its value is
     * not checked — the runtime never reads it there. {@code text} finishes "ignored unless …", and {@code setTo}
     * finishes the instruction "or set …" — a sentence that reads right for one, and for several, values.
     */
    public record Gate(String text, String setTo, Predicate<JsonNode> test) {
        static Gate equalTo(String otherFieldPath, String... values) {
            List<String> allowed = List.of(values);
            return new Gate(allowed.size() == 1 ? otherFieldPath + " = " + allowed.get(0)
                                                : otherFieldPath + " is " + String.join(" or ", allowed),
                otherFieldPath + " to " + String.join(" or ", allowed),
                block -> block.has(otherFieldPath) && allowed.contains(block.get(otherFieldPath).asText()));
        }
    }

    /** A warning raised when the key is given (non-blank) in a block where {@code test} holds. */
    public record Advice(Predicate<JsonNode> test, String message, List<String> hints) {}

    /** At least one of {@code names} is given (a non-blank string) whenever {@code test} holds for the block. */
    public record AtLeastOneOf(List<String> names, String text, Predicate<JsonNode> test, String hint) {}

    public ConfigDef require(String name, Type type, Importance imp, String doc) {
        return put(FieldSpec.of(name, type, imp, doc).asRequired());
    }

    public ConfigDef optional(String name, Type type, Importance imp, String doc, Object defaultValue) {
        return put(FieldSpec.of(name, type, imp, doc).withDefault(defaultValue));
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
        return put(FieldSpec.of(name, type, imp, doc).withDefault(defaultValue).withHints(List.of(hint)));
    }

    public ConfigDef requireEnum(String name, Importance imp, String doc, String... allowed) {
        Function<JsonNode, Optional<String>> v = node -> {
            String s = node.asText();
            return Arrays.asList(allowed).contains(s)
                ? Optional.empty()
                : Optional.of("must be one of: " + String.join(", ", allowed));
        };
        return put(FieldSpec.of(name, Type.STRING, imp, doc).asRequired().withCheck(v)
            .withHints(Arrays.asList(allowed)).withAllowed(Arrays.asList(allowed)));
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
        return put(FieldSpec.of(name, Type.NESTED, imp, doc).asRequired().withNested(sub));
    }

    /**
     * A block that may be omitted, with its key set attached. Without this, an optional block can
     * only be declared as {@code optional(name, Type.NESTED, …)} — which leaves {@code nested} null,
     * so {@link ConfigDefRunner}'s recursion (and the unknown-key sweep with it) is unreachable for
     * the whole block, and the sweep has to be called by hand from a cross-field rule instead.
     */
    /** A required field written as a non-blank string, or as an object with the key set {@code sub} (#1630). */
    public ConfigDef requireStringOrNested(String name, Importance imp, String doc, ConfigDef sub) {
        Function<JsonNode, Optional<String>> notBlank = v -> v.isTextual() && v.asText().isBlank()
            ? Optional.of("must not be blank") : Optional.empty();
        return put(FieldSpec.of(name, Type.STRING_OR_NESTED, imp, doc).asRequired().withCheck(notBlank).withNested(sub));
    }

    public ConfigDef optionalNested(String name, Importance imp, String doc, ConfigDef sub) {
        return put(FieldSpec.of(name, Type.NESTED, imp, doc).withNested(sub));
    }

    public ConfigDef requireWhen(String name, Type type, Importance imp, String doc,
                                  String otherFieldPath, String otherFieldValue) {
        return optional(name, type, imp, doc, null).requiredWhen(otherFieldPath, otherFieldValue);
    }

    public ConfigDef requireCustom(String name, Type type, Importance imp, String doc,
                                    Function<JsonNode, Optional<String>> validator) {
        return put(FieldSpec.of(name, type, imp, doc).asRequired().withCheck(validator));
    }

    public ConfigDef optionalCustom(String name, Type type, Importance imp, String doc,
                                     Function<JsonNode, Optional<String>> validator) {
        return put(FieldSpec.of(name, type, imp, doc).withCheck(validator));
    }

    /**
     * Every field of {@code other} not declared here yet, declared optional: same type, check, hint and sub-def, never
     * required; on a name both declare, the first declaration wins. For a block of defaults over another block's key set ({@code spec.defaults} of rest-generic, #1461) —
     * reusing that set rather than copying it.
     */
    public ConfigDef optionalAll(ConfigDef other) {
        for (FieldSpec f : other.fields()) {
            fields.putIfAbsent(f.name(), f.asOptional());
        }
        return this;
    }

    /** Undeclares {@code names} — for a key set built from another's ({@link #optionalAll}) that keeps only part of it. */
    public ConfigDef without(Collection<String> names) {
        fields.keySet().removeAll(names);
        return this;
    }

    private ConfigDef put(FieldSpec f) {
        fields.put(f.name(), f);
        last = f.name();
        return this;
    }

    private ConfigDef onLast(Function<FieldSpec, FieldSpec> change) {
        Objects.requireNonNull(last, "declare a field before attaching a condition to it");
        fields.put(last, change.apply(fields.get(last)));
        return this;
    }

    /** The field declared last is required when {@code otherField} holds one of {@code values}. */
    public ConfigDef requiredWhen(String otherField, String... values) {
        RequiredWhen when = values.length == 1
            ? RequiredWhen.equalTo(otherField, values[0])
            : RequiredWhen.oneOf(otherField, List.of(values));
        return onLast(f -> f.withRequiredWhen(when));
    }

    /**
     * The field declared last is required when {@code holds} says so of its block — for a condition one
     * sibling's value cannot express. {@code text} finishes the sentence "required when …".
     */
    public ConfigDef requiredWhen(String text, Predicate<JsonNode> holds) {
        return onLast(f -> f.withRequiredWhen(new RequiredWhen(null, null, text, holds)));
    }

    /**
     * A list whose every element is an object of the shape {@code element}: each is checked at its own
     * path ({@code indexes[2].columns}), and one that is no object is refused where it sits. Optional.
     */
    public ConfigDef optionalListOf(String name, Importance imp, String doc, ConfigDef element) {
        return put(FieldSpec.of(name, Type.LIST, imp, doc).withNested(element));
    }

    /** Like {@link #optionalListOf}, and the list must be given. */
    public ConfigDef requireListOf(String name, Importance imp, String doc, ConfigDef element) {
        return put(FieldSpec.of(name, Type.LIST, imp, doc).asRequired().withNested(element));
    }

    /** The list declared last must not be empty (an absent one is the declaration's business, not this check's). */
    public ConfigDef nonEmpty() {
        return alsoCheck(v -> v.isArray() && v.isEmpty() ? Optional.of("must not be empty") : Optional.empty());
    }

    /** Every item of the list declared last satisfies {@code ok}; the first that does not is named by its index. */
    public ConfigDef eachItemIs(String what, Predicate<JsonNode> ok) {
        return alsoCheck(v -> {
            for (int i = 0; i < v.size(); i++) {
                if (!ok.test(v.get(i))) return Optional.of("item " + i + " must be " + what);
            }
            return Optional.empty();
        });
    }

    /** Adds a check to the field declared last: the checks run in the order they were declared, the first refusal wins. */
    private ConfigDef alsoCheck(Function<JsonNode, Optional<String>> next) {
        return onLast(f -> {
            Function<JsonNode, Optional<String>> before = f.validator();
            if (before == null) return f.withCheck(next);
            return f.withCheck(v -> {
                Optional<String> refusal = before.apply(v);
                return refusal.isPresent() ? refusal : next.apply(v);
            });
        });
    }

    /**
     * The field declared last is read only when {@code otherField} holds one of {@code values}. Given in any other
     * block it is named as ignored (a warning) and its value is left unchecked; a key with no value is left alone.
     */
    public ConfigDef ignoredUnless(String otherField, String... values) {
        return onLast(f -> f.withGate(Gate.equalTo(otherField, values)));
    }

    /**
     * A warning whenever the field declared last is given (non-blank) in a block where {@code otherField} holds
     * {@code value} — for a literal secret, which is valid and still worth a word.
     */
    public ConfigDef warnWhenGiven(String otherField, String value, String message, String... hints) {
        RequiredWhen when = RequiredWhen.equalTo(otherField, value);
        return onLast(f -> f.withAdvice(new Advice(when.test(), message, List.of(hints))));
    }

    /** The value of the field declared last is one of {@code allowed}; the refusal lists them, and so do its hints. */
    public ConfigDef oneOf(String... allowed) {
        List<String> values = List.of(allowed);
        alsoCheck(v -> values.contains(v.asText()) ? Optional.empty() : Optional.of("must be one of: " + String.join(", ", values)));
        return onLast(f -> (f.recommendations().isEmpty() ? f.withHints(values) : f).withAllowed(values));
    }

    /** Another check on the field declared last, after those declared before it: the first refusal wins. */
    public ConfigDef check(Function<JsonNode, Optional<String>> check) {
        return alsoCheck(check);
    }

    /** What the field declared last teaches when it is refused, missing or mistyped. */
    public ConfigDef hint(String... sentences) {
        return onLast(f -> f.withHints(List.of(sentences)));
    }

    /**
     * A key the runtime never reads: giving it is refused, with these words, instead of being ignored.
     * A key left empty declares nothing and is not refused. Not a setting: the hub does not list it
     * among the fields one can set.
     */
    public ConfigDef refused(String name, String message, String... hints) {
        return put(FieldSpec.of(name, Type.STRING, Importance.LOW, message).withRefusal(message)
            .withHints(List.of(hints)));
    }

    /** Whenever {@code when} holds for the block, at least one of {@code names} must be given (non-blank). */
    public ConfigDef atLeastOneOf(String text, Predicate<JsonNode> when, String hint, String... names) {
        atLeastOneOf.add(new AtLeastOneOf(List.of(names), text, when, hint));
        return this;
    }

    public List<AtLeastOneOf> atLeastOneOf() { return atLeastOneOf; }

    public Collection<FieldSpec> fields() { return fields.values(); }

    private boolean closed;
    private boolean describesOnly;

    /**
     * Marks this definition as a DESCRIPTION: the hub publishes it (a list's element shape, a block's keys) and
     * {@link ConfigDefRunner} does not descend into it from the field that carries it. For a shape whose rules are
     * enforced elsewhere on another node — jdbc checks a table laid over its {@code defaults}, so a declaration run on
     * the table as written would refuse what the run accepts, and check it twice. Called directly, the definition still
     * checks: only the descent is skipped.
     */
    public ConfigDef describesOnly() { this.describesOnly = true; return this; }
    public boolean isDescribesOnly() { return describesOnly; }

    /**
     * Marks this block's key set as COMPLETE: the runner warns on any key not declared
     * here (C3, closed-blocks-first — only call this once every runtime-accepted key is
     * declared, or the warning lies).
     */
    public ConfigDef closed() { this.closed = true; return this; }
    public boolean isClosed() { return closed; }
    public boolean declares(String name) { return fields.containsKey(name); }
}
