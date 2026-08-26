package io.lumnik.common.tenant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantContextTest {

    @Test
    void runAsSystem_setsAndClearsFlag() {
        assertFalse(TenantContext.isSystemMode(), "default state must be non-system");

        TenantContext.runAsSystem(() -> {
            assertTrue(TenantContext.isSystemMode(), "flag must be true inside runAsSystem");
        });

        assertFalse(TenantContext.isSystemMode(), "flag must be cleared after runAsSystem");
    }

    @Test
    void runAsSystem_clearsFlagEvenOnException() {
        try {
            TenantContext.runAsSystem(() -> { throw new RuntimeException("boom"); });
        } catch (RuntimeException expected) {
            // expected
        }
        assertFalse(TenantContext.isSystemMode(), "flag must be cleared after exception");
    }

    @Test
    void runAsSystem_nestedCallsRestorePreviousState() {
        TenantContext.runAsSystem(() -> {
            assertTrue(TenantContext.isSystemMode());
            TenantContext.runAsSystem(() -> {
                assertTrue(TenantContext.isSystemMode());
            });
            assertTrue(TenantContext.isSystemMode(), "outer scope still in system mode");
        });
        assertFalse(TenantContext.isSystemMode());
    }
}
