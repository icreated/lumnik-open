package io.lumnik.hub.connector.rest.auth;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SecretRedactorTest {
    @Test void redacts_bearer() { assertEquals("Bearer <redacted>", SecretRedactor.redactHeader("Authorization", "Bearer sk_live_xyz")); }
    @Test void redacts_api_key() { assertEquals("<redacted>", SecretRedactor.redactHeader("X-API-Key", "secret")); }
    @Test void passthrough()    { assertEquals("application/json", SecretRedactor.redactHeader("Content-Type", "application/json")); }
}
