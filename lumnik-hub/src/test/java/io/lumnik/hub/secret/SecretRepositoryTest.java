package io.lumnik.hub.secret;

import io.lumnik.common.tenant.TenantContext;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class SecretRepositoryTest {

    @Inject SecretRepository repo;
    @Inject EntityManager em;
    @Inject UserTransaction utx;

    @Test
    void persist_and_find_by_name() throws Exception {
        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();
                em.createNativeQuery("SET LOCAL app.current_tenant = 1").executeUpdate();
                em.createNativeQuery(
                    "INSERT INTO platform.secret(id, tenant_id, org_id, name, value_encrypted) " +
                    "VALUES (gen_random_uuid(), 1, 0, 'STRIPE_API_KEY', " +
                    "        pgp_sym_encrypt('sk_test_xyz', 'master-key-test-ok', 'cipher-algo=aes256'))"
                ).executeUpdate();
                utx.commit();
            } catch (Exception e) {
                try { utx.rollback(); } catch (Exception ignored) {}
                throw new RuntimeException(e);
            }
        });

        TenantContext.callAsSystem(() -> {
            var found = repo.findByName(1L, "STRIPE_API_KEY");
            assertTrue(found.isPresent());
            assertEquals("STRIPE_API_KEY", found.get().getName());
            return null;
        });
    }

    @Test
    void soft_delete_hides_from_findByName() throws Exception {
        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();
                em.createNativeQuery("SET LOCAL app.current_tenant = 1").executeUpdate();
                em.createNativeQuery(
                    "INSERT INTO platform.secret(id, tenant_id, org_id, name, value_encrypted, deleted_at) " +
                    "VALUES (gen_random_uuid(), 1, 0, 'DELETED_KEY', " +
                    "        pgp_sym_encrypt('x', 'master-key-test-ok', 'cipher-algo=aes256'), now())"
                ).executeUpdate();
                utx.commit();
            } catch (Exception e) {
                try { utx.rollback(); } catch (Exception ignored) {}
                throw new RuntimeException(e);
            }
        });

        TenantContext.callAsSystem(() -> {
            assertTrue(repo.findByName(1L, "DELETED_KEY").isEmpty());
            return null;
        });
    }
}
