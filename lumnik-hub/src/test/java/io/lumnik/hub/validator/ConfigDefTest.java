package io.lumnik.hub.validator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;
import io.lumnik.hub.validator.ConfigDef.FieldSpec;
import io.lumnik.hub.validator.ConfigDef.Importance;
import io.lumnik.hub.validator.ConfigDef.RequiredWhen;
import io.lumnik.hub.validator.ConfigDef.Type;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigDefTest {

    @Test void require_addsFieldAsRequired() {
        ConfigDef def = new ConfigDef()
            .require("host", Type.STRING, Importance.HIGH, "Server host");
        var f = def.fields().iterator().next();
        assertEquals("host", f.name());
        assertTrue(f.required());
        assertEquals(Type.STRING, f.type());
    }

    @Test void optional_addsFieldAsOptionalWithDefault() {
        ConfigDef def = new ConfigDef()
            .optional("port", Type.INT, Importance.LOW, "Port", 5432);
        var f = def.fields().iterator().next();
        assertFalse(f.required());
        assertEquals(5432, f.defaultValue());
    }

    @Test void optionalHinted_carriesTheHintAsARecommendation() {
        ConfigDef def = new ConfigDef()
            .optionalHinted("headers", Type.LIST, Importance.MEDIUM, "Static headers", null,
                "headers is a list of { name, value|value_env } objects");
        var f = def.fields().iterator().next();
        assertFalse(f.required());
        assertEquals(List.of("headers is a list of { name, value|value_env } objects"),
            f.recommendations(), "the hint is what makes a type refusal teach");
    }

    /**
     * The guard, tested rather than trusted: `List.of(null)` would throw from inside the JDK at
     * the first validate request, naming neither the key nor the method. Fail at the declaration,
     * and name the alternative.
     */
    @Test void optionalHinted_withoutAHint_failsAtTheDeclaration() {
        var e = assertThrows(NullPointerException.class, () -> new ConfigDef()
            .optionalHinted("headers", Type.LIST, Importance.MEDIUM, "Static headers", null, null));
        // The type alone proves nothing — List.of(null) throws NullPointerException too, with no
        // message at all. The MESSAGE is the whole guard, so the message is what is asserted.
        assertNotNull(e.getMessage(), "an unguarded List.of(null) throws without a message");
        assertTrue(e.getMessage().contains("headers") && e.getMessage().contains("optional()"),
            () -> "the refusal must name the key and what to call instead, got: " + e.getMessage());
    }

    @Test void requireEnum_setsRecommendationsToAllowedValues() {
        ConfigDef def = new ConfigDef()
            .requireEnum("kind", Importance.HIGH, "Auth kind", "bearer", "basic");
        var f = def.fields().iterator().next();
        assertTrue(f.required());
        assertEquals(2, f.recommendations().size());
    }

    @Test void requireNested_carriesSubDef() {
        ConfigDef sub = new ConfigDef().require("x", Type.STRING, Importance.HIGH, "x");
        ConfigDef def = new ConfigDef().requireNested("auth", Importance.HIGH, "Auth", sub);
        var f = def.fields().iterator().next();
        assertEquals(Type.NESTED, f.type());
        assertNotNull(f.nested());
    }

    @Test void requireWhen_carriesCondition() {
        ConfigDef def = new ConfigDef().requireWhen(
            "token_env", Type.STRING, Importance.HIGH, "Token env", "kind", "bearer");
        var f = def.fields().iterator().next();
        assertFalse(f.required());
        assertNotNull(f.requiredWhen());
        assertEquals("kind", f.requiredWhen().otherFieldPath());
    }

    // -- every declaration builds exactly this spec (#1731) -----------------------------------------
    // The validator and a condition's test are lambdas, so they are compared by presence, not by value.

    private static final ConfigDef SUB = new ConfigDef().require("k", Type.STRING, Importance.LOW, "sub");

    private static FieldSpec spec(String name, Type type, Object dflt, boolean required, boolean checked,
                                            RequiredWhen when, List<String> hints, ConfigDef nested) {
        return new FieldSpec(name, type, Importance.HIGH, "doc", dflt, required, checked ? s -> Optional.empty() : null,
            when, hints, nested, null, null, null, List.of());
    }

    static Stream<Arguments> declarations() {
        var when = RequiredWhen.equalTo("mode", "watermark");
        return Stream.of(
            Arguments.of("require",
                new ConfigDef().require("f", Type.INT, Importance.HIGH, "doc"),
                spec("f", Type.INT, null, true, false, null, List.of(), null)),
            Arguments.of("optional",
                new ConfigDef().optional("f", Type.INT, Importance.HIGH, "doc", 7),
                spec("f", Type.INT, 7, false, false, null, List.of(), null)),
            Arguments.of("optionalHinted",
                new ConfigDef().optionalHinted("f", Type.LIST, Importance.HIGH, "doc", null, "teach"),
                spec("f", Type.LIST, null, false, false, null, List.of("teach"), null)),
            Arguments.of("requireEnum",
                new ConfigDef().requireEnum("f", Importance.HIGH, "doc", "a", "b"),
                spec("f", Type.STRING, null, true, true, null, List.of("a", "b"), null).withAllowed(List.of("a", "b"))),
            Arguments.of("requireNested",
                new ConfigDef().requireNested("f", Importance.HIGH, "doc", SUB),
                spec("f", Type.NESTED, null, true, false, null, List.of(), SUB)),
            Arguments.of("ignoredUnless",
                new ConfigDef().optional("f", Type.STRING, Importance.HIGH, "doc", null).ignoredUnless("kind", "bearer", "oauth2"),
                new FieldSpec("f", Type.STRING, Importance.HIGH, "doc", null, false, null, null, List.of(), null, null,
                    new ConfigDef.Gate("kind is bearer or oauth2", "kind to bearer or oauth2", null), null, List.of())),
            Arguments.of("warnWhenGiven",
                new ConfigDef().optional("f", Type.STRING, Importance.HIGH, "doc", null).warnWhenGiven("kind", "bearer", "msg", "h1", "h2"),
                new FieldSpec("f", Type.STRING, Importance.HIGH, "doc", null, false, null, null, List.of(), null, null,
                    null, new ConfigDef.Advice(null, "msg", List.of("h1", "h2")), List.of())),
            Arguments.of("optionalListOf",
                new ConfigDef().optionalListOf("f", Importance.HIGH, "doc", SUB),
                spec("f", Type.LIST, null, false, false, null, List.of(), SUB)),
            Arguments.of("optionalNested",
                new ConfigDef().optionalNested("f", Importance.HIGH, "doc", SUB),
                spec("f", Type.NESTED, null, false, false, null, List.of(), SUB)),
            Arguments.of("requireStringOrNested",
                new ConfigDef().requireStringOrNested("f", Importance.HIGH, "doc", SUB),
                spec("f", Type.STRING_OR_NESTED, null, true, true, null, List.of(), SUB)),
            Arguments.of("requireWhen",
                new ConfigDef().requireWhen("f", Type.STRING, Importance.HIGH, "doc", "mode", "watermark"),
                spec("f", Type.STRING, null, false, false, when, List.of(), null)),
            Arguments.of("requireCustom",
                new ConfigDef().requireCustom("f", Type.STRING, Importance.HIGH, "doc", s -> Optional.empty()),
                spec("f", Type.STRING, null, true, true, null, List.of(), null)),
            Arguments.of("optionalCustom",
                new ConfigDef().optionalCustom("f", Type.STRING, Importance.HIGH, "doc", s -> Optional.empty()),
                spec("f", Type.STRING, null, false, true, null, List.of(), null)),
            // optionalAll copies another def's field as optional: same type, check, hint and sub-def, never required
            Arguments.of("optionalAll",
                new ConfigDef().optionalAll(new ConfigDef().requireEnum("f", Importance.HIGH, "doc", "a", "b")),
                spec("f", Type.STRING, null, false, true, null, List.of("a", "b"), null).withAllowed(List.of("a", "b"))),
            Arguments.of("optionalAll over a conditional field",
                new ConfigDef().optionalAll(new ConfigDef().requireWhen("f", Type.STRING, Importance.HIGH, "doc", "mode", "watermark")),
                spec("f", Type.STRING, null, false, false, null, List.of(), null)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("declarations")
    void eachDeclarationBuildsExactlyThisSpec(String declaration, ConfigDef def, FieldSpec expected) {
        var built = def.fields().iterator().next();
        assertThat(built).usingRecursiveComparison()
            .ignoringFields("validator", "requiredWhen.test", "gate.test", "advice.test").isEqualTo(expected);
        assertEquals(expected.validator() != null, built.validator() != null,
            declaration + ": a check is attached exactly when expected");
    }
}
