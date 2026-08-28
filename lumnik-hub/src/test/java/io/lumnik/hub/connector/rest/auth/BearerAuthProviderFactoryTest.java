package io.lumnik.hub.connector.rest.auth;

import io.lumnik.hub.connector.rest.RestRequest;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class BearerAuthProviderFactoryTest {
    @Test void signs_with_bearer_token() {
        AuthSpec spec = new AuthSpec("bearer", Map.of("token_env", "TB"), null);
        AuthProvider p = new BearerAuthProviderFactory(n -> "TB".equals(n) ? "sk_abc" : null, name -> Optional.empty()).build(spec);
        assertEquals("Bearer sk_abc", p.apply(RestRequest.get("https://x")).headers().get("Authorization"));
    }
    @Test void probe_throws_when_env_missing() {
        AuthSpec spec = new AuthSpec("bearer", Map.of("token_env", "MISSING"), null);
        AuthProvider p = new BearerAuthProviderFactory(n -> null, name -> Optional.empty()).build(spec);
        assertThrows(AuthConfigException.class, p::probe);
    }
}
