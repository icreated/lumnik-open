package io.lumnik.hub.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import io.lumnik.hub.validator.ConfigDef.Importance;
import io.lumnik.hub.validator.ConfigDef.Type;
import io.lumnik.hub.validator.ValidationError.Severity;
import static org.junit.jupiter.api.Assertions.*;

class ConfigDefRunnerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private List<ValidationError> run(ConfigDef def, String json) throws Exception {
        return ConfigDefRunner.run(def, mapper.readTree(json), "spec");
    }

    @Test void requiredFieldMissing_producesError() throws Exception {
        var def = new ConfigDef().require("host", Type.STRING, Importance.HIGH, "Host");
        var errs = run(def, "{}");
        assertEquals(1, errs.size());
        assertEquals("spec.host", errs.get(0).path());
        assertEquals(Severity.ERROR, errs.get(0).severity());
        assertTrue(errs.get(0).message().contains("required"));
    }

    @Test void requiredFieldPresent_noError() throws Exception {
        var def = new ConfigDef().require("host", Type.STRING, Importance.HIGH, "Host");
        assertTrue(run(def, "{\"host\":\"localhost\"}").isEmpty());
    }

    @Test void requiredWhen_conditionMet_thenMissing_producesError() throws Exception {
        var def = new ConfigDef()
            .require("kind", Type.STRING, Importance.HIGH, "Kind")
            .requireWhen("token_env", Type.STRING, Importance.HIGH, "Token env", "kind", "bearer");
        var errs = run(def, "{\"kind\":\"bearer\"}");
        assertEquals(1, errs.size());
        assertEquals("spec.token_env", errs.get(0).path());
        assertTrue(errs.get(0).message().contains("required when kind = bearer"));
    }

    @Test void requiredWhen_conditionNotMet_skipsValidation() throws Exception {
        var def = new ConfigDef()
            .require("kind", Type.STRING, Importance.HIGH, "Kind")
            .requireWhen("token_env", Type.STRING, Importance.HIGH, "Token env", "kind", "bearer");
        assertTrue(run(def, "{\"kind\":\"basic\"}").isEmpty());
    }

    @Test void typeMismatch_string_givenInt_producesError() throws Exception {
        var def = new ConfigDef().require("host", Type.STRING, Importance.HIGH, "Host");
        var errs = run(def, "{\"host\":42}");
        assertEquals(1, errs.size());
        assertTrue(errs.get(0).message().contains("type"));
    }

    @Test void typeMismatch_object_givenString_producesError() throws Exception {
        var sub = new ConfigDef().require("x", Type.STRING, Importance.HIGH, "x");
        var def = new ConfigDef().requireNested("auth", Importance.HIGH, "Auth", sub);
        var errs = run(def, "{\"auth\":\"oops\"}");
        assertEquals(1, errs.size());
        assertTrue(errs.get(0).message().contains("type"));
    }

    @Test void customValidator_failure_producesError() throws Exception {
        var def = new ConfigDef().requireCustom("port", Type.INT, Importance.HIGH, "Port",
            v -> v.asInt() > 0 ? Optional.empty() : Optional.of("must be > 0"));
        var errs = run(def, "{\"port\":0}");
        assertEquals(1, errs.size());
        assertTrue(errs.get(0).message().contains("must be > 0"));
    }

    @Test void customValidator_success_noError() throws Exception {
        var def = new ConfigDef().requireCustom("port", Type.INT, Importance.HIGH, "Port",
            v -> v.asInt() > 0 ? Optional.empty() : Optional.of("bad"));
        assertTrue(run(def, "{\"port\":5432}").isEmpty());
    }

    @Test void nested_recursesIntoChild_withCorrectPath() throws Exception {
        var sub = new ConfigDef().require("token_env", Type.STRING, Importance.HIGH, "Token");
        var def = new ConfigDef().requireNested("auth", Importance.HIGH, "Auth", sub);
        var errs = run(def, "{\"auth\":{}}");
        assertEquals(1, errs.size());
        assertEquals("spec.auth.token_env", errs.get(0).path());
    }

    /**
     * The primitive lives in the open tree and its first caller is closed-edition kafka, which the
     * open-edition extraction prunes — so the guard belongs here, at the runner's own level.
     * Three properties, each the reason the method exists: the block may be omitted, its rules
     * apply when it is present, and — the whole point — a CLOSED sub-def gets swept. Declaring
     * the same block as {@code optional(…, Type.NESTED, …)} leaves {@code nested} null, and the
     * recursion below is then unreachable for every optional block in the codebase.
     */
    @Test void optionalNested_omitted_noError() throws Exception {
        var sub = new ConfigDef().require("token_env", Type.STRING, Importance.HIGH, "Token");
        var def = new ConfigDef().optionalNested("auth", Importance.MEDIUM, "Auth", sub);
        assertTrue(run(def, "{}").isEmpty(), "an optional block may be absent");
    }

    @Test void optionalNested_present_recursesIntoChild() throws Exception {
        var sub = new ConfigDef().require("token_env", Type.STRING, Importance.HIGH, "Token");
        var def = new ConfigDef().optionalNested("auth", Importance.MEDIUM, "Auth", sub);
        var errs = run(def, "{\"auth\":{}}");
        assertEquals(1, errs.size());
        assertEquals("spec.auth.token_env", errs.get(0).path());
    }

    @Test void optionalNested_closedChild_sweepsUnknownKey() throws Exception {
        var sub = new ConfigDef().optional("token_env", Type.STRING, Importance.HIGH, "Token", null)
            .closed();
        var def = new ConfigDef().optionalNested("auth", Importance.MEDIUM, "Auth", sub);
        var errs = run(def, "{\"auth\":{\"token_envv\":\"X\"}}");
        assertEquals(1, errs.size(), () -> "the typo inside the optional block must be named: " + errs);
        assertEquals("spec.auth.token_envv", errs.get(0).path());
        assertEquals(Severity.WARNING, errs.get(0).severity());
    }

    @Test void enumValidation_inSet_passes() throws Exception {
        var def = new ConfigDef().requireEnum("kind", Importance.HIGH, "Kind", "bearer", "basic");
        assertTrue(run(def, "{\"kind\":\"bearer\"}").isEmpty());
    }

    @Test void enumValidation_notInSet_fails() throws Exception {
        var def = new ConfigDef().requireEnum("kind", Importance.HIGH, "Kind", "bearer", "basic");
        var errs = run(def, "{\"kind\":\"oauth2\"}");
        assertEquals(1, errs.size());
        assertTrue(errs.get(0).message().contains("must be one of"));
    }

    @Test void optionalField_missing_noError() throws Exception {
        var def = new ConfigDef().optional("timeout", Type.INT, Importance.LOW, "Timeout", 30);
        assertTrue(run(def, "{}").isEmpty());
    }

    @Test void pathPrefix_emptyAtRoot_concatenates() throws Exception {
        var def = new ConfigDef().require("host", Type.STRING, Importance.HIGH, "Host");
        var errs = ConfigDefRunner.run(def, mapper.readTree("{}"), "");
        assertEquals(1, errs.size());
        assertEquals("host", errs.get(0).path());
    }

    @Test void nullValue_treatedAsMissing() throws Exception {
        var def = new ConfigDef().require("host", Type.STRING, Importance.HIGH, "Host");
        var errs = run(def, "{\"host\":null}");
        assertEquals(1, errs.size());
    }

    @Test void typeCheck_long_acceptsIntOrLong() throws Exception {
        var def = new ConfigDef().require("size", Type.LONG, Importance.LOW, "Size");
        assertTrue(run(def, "{\"size\":12345}").isEmpty());
    }

    @Test void builtin_isUrl_acceptsValid_rejectsInvalid() throws Exception {
        var def = new ConfigDef().requireCustom("u", Type.STRING, Importance.HIGH, "URL",
            ConfigDefValidators.isUrl());
        assertTrue(run(def, "{\"u\":\"https://api.example.com\"}").isEmpty());
        assertEquals(1, run(def, "{\"u\":\"not a url\"}").size());
    }

    @Test void builtin_matchesRegex_works() throws Exception {
        var def = new ConfigDef().requireCustom("id", Type.STRING, Importance.HIGH, "Id",
            ConfigDefValidators.matchesRegex("^[a-z]+$", "lowercase letters"));
        assertTrue(run(def, "{\"id\":\"abc\"}").isEmpty());
        assertEquals(1, run(def, "{\"id\":\"ABC\"}").size());
    }

    @Test void serializer_roundtrip_keepsFieldNames() {
        var def = new ConfigDef()
            .require("host", Type.STRING, Importance.HIGH, "Host")
            .optional("port", Type.INT, Importance.LOW, "Port", 5432);
        JsonNode json = ConfigDefSerializer.toJson(def);
        assertEquals(2, json.get("fields").size());
        assertEquals("host", json.get("fields").get(0).get("name").asText());
        assertTrue(json.get("fields").get(0).get("required").asBoolean());
    }
}
