package io.lumnik.hub.connector.rest.auth;

import io.lumnik.hub.connector.secret.SecretRefs;

import io.lumnik.hub.connector.rest.RestRequest;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BearerAuthProviderFactoryTest {
    @Test void signs_with_bearer_token() {
        AuthSpec spec = new AuthSpec("bearer", Map.of("token_env", "TB"), null);
        AuthProvider p = new BearerAuthProviderFactory(SecretRefs.environmentOnly(n -> "TB".equals(n) ? "sk_abc" : null)).build(spec);
        assertEquals("Bearer sk_abc", p.apply(RestRequest.get("https://x")).headers().get("Authorization"));
    }
    /** #1640: Django REST Framework (NetBox) wants "Authorization: Token <key>" and refuses Bearer. */
    @Test void signs_under_the_scheme_the_manifest_names() {
        AuthSpec spec = new AuthSpec("bearer", Map.of("token_env", "TB", "scheme", "Token"), null);
        AuthProvider p = new BearerAuthProviderFactory(SecretRefs.environmentOnly(n -> "TB".equals(n) ? "0123abcd" : null)).build(spec);
        assertEquals("Token 0123abcd", p.apply(RestRequest.get("https://x")).headers().get("Authorization"));
    }
    @Test void probe_throws_when_env_missing() {
        AuthSpec spec = new AuthSpec("bearer", Map.of("token_env", "MISSING"), null);
        AuthProvider p = new BearerAuthProviderFactory(SecretRefs.environmentOnly(n -> null)).build(spec);
        assertThrows(AuthConfigException.class, p::probe);
    }
}
