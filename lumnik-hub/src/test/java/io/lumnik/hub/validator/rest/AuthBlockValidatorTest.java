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
        assertTrue(errs.isEmpty(), () -> "token_value is honored — no warning allowed, got: " + errs);
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
}
