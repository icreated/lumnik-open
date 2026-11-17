package io.lumnik.hub.connector.rest.auth;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.secret.SecretRefs;
import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.secret.SecretAudience;
import io.lumnik.hub.testsupport.TestSecrets;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Read as a scopeless connector's: the tenant's own, served by every secret of the tenant (#2015). */
@QuarkusTest
class AuthProviderLayeredResolutionTest {

    @Inject SecretRefs refs;
    @Inject TestSecrets secrets;

    @Test
    void a_token_env_resolves_from_the_tenants_registry() {
        secrets.put(1L, "LAYERED_TEST_BEARER", "from-db");

        var factory = new BearerAuthProviderFactory(refs);

        TenantContext.callAs(1L, () -> SecretAudience.get(List.of(), () -> {
            var spec = new AuthSpec("bearer", Map.of("token_env", "LAYERED_TEST_BEARER"), null);
            AuthProvider p = factory.build(spec);
            RestRequest signed = p.apply(RestRequest.get("https://x"));
            assertEquals("Bearer from-db", signed.headers().get("Authorization"));
            return null;
        }));
    }

    /**
     * #2000: a name the registry does not hold sends no credential, whatever the hub's process holds under it — the
     * registry's master key, or a JVM property. The production wiring, not a stub: {@link SecretRefs} as injected.
     */
    @Test
    void the_hubs_environment_is_never_sent() {
        String property = "LUMNIK_LAYERED_TEST_2000";
        System.setProperty(property, "from-a-jvm-property");
        try {
            var factory = new BearerAuthProviderFactory(refs);
            for (String name : List.of("LUMNIK_SECRET_MASTER_KEY", "PATH", property)) {
                TenantContext.callAs(1L, () -> SecretAudience.get(List.of(), () -> {
                    AuthProvider p = factory.build(new AuthSpec("bearer", Map.of("token_env", name), null));
                    assertNull(p.apply(RestRequest.get("https://x")).headers().get("Authorization"), name);
                    assertThrows(AuthConfigException.class, p::probe, name);
                    return null;
                }));
            }
        } finally {
            System.clearProperty(property);
        }
    }
}
