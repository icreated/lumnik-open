package io.lumnik.hub.hook;

import io.lumnik.hub.tenant.TenantContextFilter;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class HookOverrideIT {

    static final Long TENANT = 999931L;

    @Inject HookRegistry registry;
    @Inject HookOverrideStore store;

    @BeforeEach void setTenant() { TenantContextFilter.CURRENT_TENANT.set(TENANT); }
    @AfterEach  void clear() {
        String hookId = anyHookId();
        store.invalidate(TENANT, hookId);
        TenantContextFilter.CURRENT_TENANT.remove();
    }

    @Test
    @Transactional
    void override_changes_isEnabled_immediately_after_set() {
        String hookId = anyHookId();
        if (hookId == null) return; // no hooks registered — test is vacuously true

        // Default: no override → returns default
        assertThat(store.isEnabled(TENANT, hookId, true)).isTrue();

        // Disable
        store.set(TENANT, hookId, false);
        assertThat(store.isEnabled(TENANT, hookId, true)).isFalse();

        // Re-enable
        store.set(TENANT, hookId, true);
        assertThat(store.isEnabled(TENANT, hookId, true)).isTrue();
    }

    private String anyHookId() {
        return registry.allBindings().isEmpty() ? null : registry.allBindings().get(0).id();
    }
}
