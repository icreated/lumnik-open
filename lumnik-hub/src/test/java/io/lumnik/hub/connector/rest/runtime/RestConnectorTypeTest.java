package io.lumnik.hub.connector.rest.runtime;

import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RestConnectorTypeTest {

    private final RestConnectorType type = new RestConnectorType();

    @Test void validateConfig_acceptsConnectorWithoutAuth() {
        // auth is optional — omitted means `none` at runtime (e.g. docs/connectors/rest/10-offset-none.yaml)
        assertDoesNotThrow(() -> type.validateConfig(Map.of("base_url", "https://api.example.com")));
    }

    @Test void validateConfig_requiresBaseUrl() {
        assertThrows(ConfigValidationException.class, () -> type.validateConfig(Map.of()));
    }
}
