package io.lumnik.hub.connector.rest.auth;

import io.lumnik.hub.connector.secret.SecretRefs;

import io.lumnik.hub.connector.rest.RestRequest;
import org.junit.jupiter.api.Test;
import java.util.Base64;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BasicAuthProviderFactoryTest {
    @Test void base64_encodes_user_pass() {
        AuthSpec s = new AuthSpec("basic", Map.of("username", "alice", "password_env", "P"), null);
        AuthProvider p = new BasicAuthProviderFactory(SecretRefs.environmentOnly(n -> "P".equals(n) ? "wonderland" : null)).build(s);
        String exp = "Basic " + Base64.getEncoder().encodeToString("alice:wonderland".getBytes());
        assertEquals(exp, p.apply(RestRequest.get("https://x")).headers().get("Authorization"));
    }
}
