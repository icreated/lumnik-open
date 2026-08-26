package io.lumnik.hub.tenant;

import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.common.tenant.TenantContext;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link TenantConnectionInterceptor} issues {@code SET LOCAL app.current_tenant}
 * on every JDBC connection acquired within a JTA transaction.
 *
 * <p>Thread-local is set BEFORE {@code ut.begin()} so Agroal's
 * {@code onConnectionAcquire} callback fires with the tenant already in place.
 */
@QuarkusTest
class TenantInterceptorIT {

    @Inject EntityManager em;
    @Inject UserTransaction ut;

    @AfterEach
    void clearContext() {
        TenantContextFilter.CURRENT_TENANT.remove();
        CurrentTenantHolder.clear();
    }

    @Test
    void setLocal_isIssuedOnConnectionAcquire() throws Exception {
        // Set context BEFORE starting the transaction so onConnectionAcquire reads it.
        TenantContextFilter.CURRENT_TENANT.set(42L);
        CurrentTenantHolder.set(42L);

        ut.begin();
        try {
            Object current = em.createNativeQuery(
                "SELECT current_setting('app.current_tenant', true)").getSingleResult();
            assertEquals("42", current);
        } finally {
            ut.rollback();
        }
    }

    @Test
    void setLocal_setsSystemSentinelInSystemMode() throws Exception {
        // System mode is active before tx opens — interceptor must see isSystemMode()=true.
        TenantContextFilter.CURRENT_TENANT.set(42L);
        CurrentTenantHolder.set(42L);

        String[] holder = new String[1];
        TenantContext.runAsSystem(() -> {
            try {
                ut.begin();
                holder[0] = (String) em.createNativeQuery(
                    "SELECT current_setting('app.current_tenant', true)").getSingleResult();
                ut.rollback();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // System mode is REAL since C1-RLS: the interceptor sets the cross-tenant sentinel '0'
        // (V22 policies accept it). It used to SKIP the GUC, which made every system read return
        // zero rows on an RLS-enforced hub (finding lm-connector-run-rls-404).
        assertEquals("0", holder[0],
            "Expected the system sentinel '0' GUC in system mode but got: " + holder[0]);
    }

    @Test
    void setLocal_isSkippedForNonPositiveTenant() throws Exception {
        // Sentinel spoofing guard: a request CLAIMING tenant 0 must NOT have the GUC set —
        // fail closed (empty GUC → NULLIF → zero rows), never elevate to the system sentinel.
        TenantContextFilter.CURRENT_TENANT.set(0L);
        CurrentTenantHolder.set(0L);

        ut.begin();
        try {
            Object current = em.createNativeQuery(
                "SELECT current_setting('app.current_tenant', true)").getSingleResult();
            assertTrue(current == null || current.toString().isEmpty(),
                "Expected empty GUC for claimed tenant 0 but got: " + current);
        } finally {
            ut.rollback();
        }
    }
}
