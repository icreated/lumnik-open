package io.lumnik.hub.validator.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.validator.ConfigDefRunner;
import io.lumnik.hub.validator.ValidationError;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionBlockValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test void plainPassword_isHonoredByRuntime_mustStayDeclared() throws Exception {
        // JdbcConnectorType.resolvePassword honors a plain-text `password` above password_env —
        // closing the block without declaring it would emit a lying "ignored by the runtime"
        // warning on an honored key (wave-finale inventory refutation).
        List<ValidationError> errs = ConfigDefRunner.run(
            ConnectionBlockValidator.configDef(),
            mapper.readTree("{\"url\":\"jdbc:postgresql://h/db\",\"user\":\"u\","
                + "\"password_env\":\"PW\",\"password\":\"dev-only\"}"),
            "spec.connection");
        assertTrue(errs.isEmpty(), () -> "password is honored — no warning allowed, got: " + errs);
    }

    @Test void unknownKey_warnsIgnored() throws Exception {
        List<ValidationError> errs = ConfigDefRunner.run(
            ConnectionBlockValidator.configDef(),
            mapper.readTree("{\"url\":\"jdbc:postgresql://h/db\",\"user\":\"u\","
                + "\"password_env\":\"PW\",\"pool_size\":10}"),
            "spec.connection");
        assertTrue(errs.stream().anyMatch(e ->
                e.severity() == ValidationError.Severity.WARNING
                && e.path().equals("spec.connection.pool_size")),
            () -> "expected the unknown-key warning for pool_size, got: " + errs);
    }
}
