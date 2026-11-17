package io.lumnik.hub.hook;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1992: a hook's bindings name the tenants they run for. A tenant's admin sees a binding that runs for it — as
 * global, or as its own — and never another tenant's id, nor a binding that runs for other tenants only.
 */
class HookBindingTenancyTest {

    private static HookBinding forTenants(List<Object> tenants) {
        return new HookBinding("h", "m", "", null, Set.of(), null, null,
            new HookHandler.JavaHandler("x.Y"), 100, tenants, true);
    }

    @Test
    void aGlobalBindingIsSeenAsGlobalByEveryTenant() {
        for (List<Object> global : List.<List<Object>>of(List.of(), List.of("*"), List.of("*", 7L))) {
            assertThat(forTenants(global).matchesTenant(7L)).isTrue();
            assertThat(forTenants(global).isGlobal()).isTrue();
            assertThat(forTenants(global).tenantsAsSeenBy(7L)).containsExactly("*");
        }
        assertThat(forTenants(null).tenantsAsSeenBy(7L)).containsExactly("*");
    }

    @Test
    void aBindingForSomeTenantsShowsTheCallerOnly_andNoneToAnotherTenant() {
        HookBinding b = forTenants(List.of(7L, 8L));

        assertThat(b.matchesTenant(7L)).isTrue();
        assertThat(b.tenantsAsSeenBy(7L)).containsExactly(7L);
        assertThat(b.matchesTenant(9L)).as("tenant 9 is not named: the binding is not its").isFalse();
        assertThat(b.isGlobal()).isFalse();

        HookRegistry registry = new HookRegistry();
        registry.register(b);
        registry.register(new HookBinding("g", "m", "", null, Set.of(), null, null,
            new HookHandler.JavaHandler("x.Z"), 100, List.of("*"), true));
        assertThat(registry.bindingsFor(9L)).as("tenant 9 sees the global one only").hasSize(1);
        assertThat(registry.bindingsFor(7L)).hasSize(2);
    }

    /** System mode (no tenant): a global binding runs, one for named tenants does not. */
    @Test
    void aCallWithoutATenantMatchesTheGlobalBindingsOnly() {
        assertThat(forTenants(List.of("*", 5L)).matchesTenant(null)).isTrue();
        assertThat(forTenants(List.of()).matchesTenant(null)).isTrue();
        assertThat(forTenants(List.of(5L)).matchesTenant(null)).isFalse();
    }
}
