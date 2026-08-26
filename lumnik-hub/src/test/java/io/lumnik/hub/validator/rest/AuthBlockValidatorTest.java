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
}
