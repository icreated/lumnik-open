package io.lumnik.hub.hook;

import io.lumnik.hub.tenant.TenantContextFilter;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class HookScriptOverrideIT {

    static final Long TENANT = 999970L;
    static final String HOOK_ID = "test.script.override";

    @Inject HookOverrideStore store;

    @BeforeEach void setTenant() { TenantContextFilter.CURRENT_TENANT.set(TENANT); }
    @AfterEach void clear() {
        store.deleteOverride(TENANT, HOOK_ID);
        TenantContextFilter.CURRENT_TENANT.remove();
    }

    @Test
    void findScript_returns_empty_when_no_script_stored() {
        assertThat(store.findScript(TENANT, HOOK_ID)).isEmpty();
    }

    @Test
    void setScript_then_findScript_returns_content() {
        store.setScript(TENANT, HOOK_ID, "val x = 42");
        assertThat(store.findScript(TENANT, HOOK_ID)).hasValue("val x = 42");
    }

    @Test
    void deleteOverride_removes_script() {
        store.setScript(TENANT, HOOK_ID, "val x = 1");
        store.deleteOverride(TENANT, HOOK_ID);
        assertThat(store.findScript(TENANT, HOOK_ID)).isEmpty();
    }
}
