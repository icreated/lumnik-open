package io.lumnik.hub.connector.rest.auth;

import io.lumnik.hub.connector.secret.SecretRefs;

import io.lumnik.hub.connector.rest.RestRequest;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ApiKeyAuthProviderFactoryTest {
    @Test void adds_header_key() {
        AuthSpec s = new AuthSpec("api-key", Map.of("in", "header", "name", "X-API-Key", "value_env", "K"), null);
        AuthProvider p = new ApiKeyAuthProviderFactory(SecretRefs.environmentOnly(n -> "K".equals(n) ? "secret" : null)).build(s);
        assertEquals("secret", p.apply(RestRequest.get("https://x")).headers().get("X-API-Key"));
    }
    @Test void adds_query_key() {
        AuthSpec s = new AuthSpec("api-key", Map.of("in", "query", "name", "apikey", "value_env", "K"), null);
        AuthProvider p = new ApiKeyAuthProviderFactory(SecretRefs.environmentOnly(n -> "K".equals(n) ? "secret" : null)).build(s);
        assertEquals("secret", p.apply(RestRequest.get("https://x")).queryParams().get("apikey"));
    }
}
