package io.lumnik.hub.validator;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import io.lumnik.hub.validator.ConfigDef.Importance;
import io.lumnik.hub.validator.ConfigDef.Type;

import java.util.List;

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
}
