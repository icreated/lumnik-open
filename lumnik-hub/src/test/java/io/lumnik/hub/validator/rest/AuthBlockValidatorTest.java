package io.lumnik.hub.validator.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.validator.ConfigDefRunner;
import io.lumnik.hub.validator.ValidationError;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AuthBlockValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test void bearer_tokenEnv_isHonoredByRuntime_mustStayDeclared() throws Exception {
        // token_env / token_value are read by the bearer provider but were only
        // cross-field-checked — closing the block without declaring them would emit a lying
        // "ignored by the runtime" warning (wave-finale inventory).
        List<ValidationError> errs = ConfigDefRunner.run(
            AuthBlockValidator.configDef(),
            mapper.readTree("{\"kind\":\"bearer\",\"token_env\":\"API_TOKEN\"}"),
            "spec.auth");
        assertTrue(errs.isEmpty(), () -> "token_env is honored — no warning allowed, got: " + errs);
    }

    @Test void bearer_tokenValue_devOnly_staysClean() throws Exception {
        List<ValidationError> errs = ConfigDefRunner.run(
            AuthBlockValidator.configDef(),
            mapper.readTree("{\"kind\":\"bearer\",\"token_value\":\"dev-token\"}"),
            "spec.auth");
        // Honored: never called an unknown key. It is still a literal secret, and the block says so once.
        assertTrue(errs.stream().noneMatch(e -> e.message().equals(ConfigDefRunner.UNKNOWN_KEY)),
            () -> "token_value is honored — no unknown-key warning allowed, got: " + errs);
        assertEquals(List.of("spec.auth.token_value"), errs.stream().map(ValidationError::path).toList(), () -> errs.toString());
        assertSame(ValidationError.Severity.WARNING, errs.get(0).severity());
    }

    @Test void unknownKey_warnsIgnored() throws Exception {
        List<ValidationError> errs = ConfigDefRunner.run(
            AuthBlockValidator.configDef(),
            mapper.readTree("{\"kind\":\"bearer\",\"token_env\":\"API_TOKEN\",\"token\":\"x\"}"),
            "spec.auth");
        assertTrue(errs.stream().anyMatch(e ->
                e.severity() == ValidationError.Severity.WARNING
                && e.path().equals("spec.auth.token")),
            () -> "expected the unknown-key warning for token, got: " + errs);
    }

    // ── oauth2 (#1393) ──

    private List<ValidationError> oauth2(String extra) throws Exception {
        return ConfigDefRunner.run(AuthBlockValidator.configDef(), mapper.readTree(
            "{\"kind\":\"oauth2\",\"token_url\":\"https://auth.example/oauth/token\",\"client_id\":\"lumnik\","
                + "\"client_secret_env\":\"SAAS_SECRET\"" + extra + "}"), "spec.auth");
    }

    @Test void oauth2_with_its_keys_validates_clean() throws Exception {
        List<ValidationError> errs = oauth2(",\"scope\":\"orders.read\",\"client_auth\":\"post\"");
        assertTrue(errs.isEmpty(), () -> "every oauth2 key is honored, got: " + errs);
    }

    @Test void oauth2_without_its_secret_reference_is_refused() throws Exception {
        List<ValidationError> errs = ConfigDefRunner.run(AuthBlockValidator.configDef(), mapper.readTree(
            "{\"kind\":\"oauth2\",\"token_url\":\"https://a.example/t\",\"client_id\":\"lumnik\"}"), "spec.auth");
        assertTrue(errs.stream().anyMatch(e -> e.severity() == ValidationError.Severity.ERROR
            && e.path().equals("spec.auth.client_secret_env")), () -> "expected client_secret_env required, got: " + errs);
    }

    @Test void oauth2_client_secret_inline_is_not_a_key() throws Exception {
        List<ValidationError> errs = oauth2(",\"client_secret\":\"pasted\"");
        assertTrue(errs.stream().anyMatch(e -> e.path().equals("spec.auth.client_secret")),
            () -> "an inline secret must be flagged, got: " + errs);
    }

    // ── session (#1751) ──

    private List<ValidationError> run(String json) throws Exception {
        return ConfigDefRunner.run(AuthBlockValidator.configDef(), mapper.readTree(json), "spec.auth");
    }

    @Test void session_with_every_key_validates_clean() throws Exception {
        List<ValidationError> errs = run("{\"kind\":\"session\",\"login_url\":\"http://app.test/j_security_check\","
            + "\"prime_url\":\"http://app.test/vets.html\",\"username\":\"lumnik\",\"password_env\":\"APP_PASSWORD\","
            + "\"username_field\":\"j_username\",\"password_field\":\"j_password\",\"csrf_field\":\"_csrf\"}");
        assertTrue(errs.isEmpty(), () -> "every session key is honored, got: " + errs);
    }

    @Test void session_requires_its_login_url_username_and_password_reference() throws Exception {
        List<ValidationError> errs = run("{\"kind\":\"session\"}");
        assertEquals(List.of("spec.auth.login_url", "spec.auth.password_env", "spec.auth.username"),
            errs.stream().filter(e -> e.severity() == ValidationError.Severity.ERROR).map(ValidationError::path).sorted().toList(),
            () -> errs.toString());
    }

    @Test void the_session_keys_are_named_ignored_under_another_kind() throws Exception {
        List<ValidationError> errs = run("{\"kind\":\"basic\",\"username\":\"u\",\"password_env\":\"P\","
            + "\"login_url\":\"http://app.test/login\",\"csrf_field\":\"_csrf\"}");
        assertEquals(List.of("spec.auth.csrf_field", "spec.auth.login_url"),
            errs.stream().filter(e -> e.severity() == ValidationError.Severity.WARNING).map(ValidationError::path).sorted().toList(),
            () -> errs.toString());
    }
}
