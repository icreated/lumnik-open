package io.lumnik.hub.secret;

import io.lumnik.common.tenant.TenantContext;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class PgcryptoSecretResolverIT {

    @Inject SecretResolver resolver;
    @Inject EntityManager em;
    @Inject UserTransaction utx;

    @Test
    void resolves_existing_secret_with_round_trip_encryption() throws Exception {
        // Seed via runAsSystem (admin DS bypasses RLS)
        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();
                em.createNativeQuery("SET LOCAL app.current_tenant = 1").executeUpdate();
                em.createNativeQuery(
                    "INSERT INTO platform.secret(id, tenant_id, org_id, name, value_encrypted) " +
                    "VALUES (gen_random_uuid(), 1, 0, 'TEST_KEY_RESOLVE', " +
                    "        pgp_sym_encrypt('hello-secret-world', 'master-key-test-ok', 'cipher-algo=aes256'))"
                ).executeUpdate();
                utx.commit();
            } catch (Exception e) {
                try { utx.rollback(); } catch (Exception ignored) {}
                throw new RuntimeException(e);
            }
        });

        // Resolve under tenant=1 context using TenantContext.callAs
        Optional<String> resolved = TenantContext.callAs(1L, () -> resolver.resolve("TEST_KEY_RESOLVE"));

        assertTrue(resolved.isPresent(), "Secret should be found for tenant 1");
        assertEquals("hello-secret-world", resolved.get());
    }

    @Test
    void returns_empty_for_missing_name() {
        Optional<String> result = TenantContext.callAs(1L, () -> resolver.resolve("NOT_DEFINED_KEY_XYZ"));
        assertTrue(result.isEmpty(), "Unknown secret should return empty");
    }
}
