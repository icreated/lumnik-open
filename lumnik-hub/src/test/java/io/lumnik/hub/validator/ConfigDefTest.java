package io.lumnik.hub.validator;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import io.lumnik.hub.validator.ConfigDef.Importance;
import io.lumnik.hub.validator.ConfigDef.Type;

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
