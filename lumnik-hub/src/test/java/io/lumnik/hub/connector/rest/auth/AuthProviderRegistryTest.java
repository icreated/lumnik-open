package io.lumnik.hub.connector.rest.auth;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class AuthProviderRegistryTest {
    @Inject AuthProviderRegistry registry;
    @Test void unknown_kind_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> registry.build(new AuthSpec("nonsense", Map.of(), null)));
    }
}
