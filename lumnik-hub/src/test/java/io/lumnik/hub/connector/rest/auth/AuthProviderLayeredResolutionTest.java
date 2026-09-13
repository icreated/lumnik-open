package io.lumnik.hub.connector.rest.auth;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.secret.SecretRefs;
import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.secret.SecretResolver;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class AuthProviderLayeredResolutionTest {

    @Inject SecretResolver resolver;
    @Inject EntityManager em;
    @Inject UserTransaction utx;

    @Test
    void db_secret_beats_env_var_fallback() throws Exception {
        // Seed DB: token name LAYERED_TEST_BEARER → "from-db"
        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();
                em.createNativeQuery("SET LOCAL app.current_tenant = 1").executeUpdate();
                em.createNativeQuery(
                    "INSERT INTO platform.secret(id, tenant_id, org_id, name, value_encrypted) " +
                    "VALUES (gen_random_uuid(), 1, 0, 'LAYERED_TEST_BEARER', " +
                    "        pgp_sym_encrypt('from-db', 'master-key-test-ok', 'cipher-algo=aes256'))"
                ).executeUpdate();
                utx.commit();
            } catch (Exception e) {
                try { utx.rollback(); } catch (Exception ignored) {}
                throw new RuntimeException(e);
            }
        });

        // Build factory with env lookup that would return "from-env" if used
        var factory = new BearerAuthProviderFactory(new SecretRefs(
            resolver,
            name -> "LAYERED_TEST_BEARER".equals(name) ? "from-env" : null));

        TenantContext.callAs(1L, () -> {
            var spec = new AuthSpec("bearer", Map.of("token_env", "LAYERED_TEST_BEARER"), null);
            AuthProvider p = factory.build(spec);
            RestRequest signed = p.apply(RestRequest.get("https://x"));
            // DB value wins over env
            assertEquals("Bearer from-db", signed.headers().get("Authorization"));
            return null;
        });
    }

    @Test
    void env_var_fallback_when_db_miss() {
        var factory = new BearerAuthProviderFactory(new SecretRefs(
            resolver,
            name -> "NOT_IN_DB_BEARER".equals(name) ? "from-env-only" : null));

        TenantContext.callAs(1L, () -> {
            var spec = new AuthSpec("bearer", Map.of("token_env", "NOT_IN_DB_BEARER"), null);
            AuthProvider p = factory.build(spec);
            RestRequest signed = p.apply(RestRequest.get("https://x"));
            assertEquals("Bearer from-env-only", signed.headers().get("Authorization"));
            return null;
        });
    }
}
