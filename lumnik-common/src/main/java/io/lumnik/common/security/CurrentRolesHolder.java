package io.lumnik.common.security;

import java.util.Set;

/**
 * Thread-local holder for the authenticated user's roles — the cross-module
 * mirror of {@link io.lumnik.common.tenant.CurrentTenantHolder}: populated by
 * the hub's auth filter, readable from modules that cannot see the hub at
 * compile time.
 */
public final class CurrentRolesHolder {

    private static final ThreadLocal<Set<String>> ROLES = new ThreadLocal<>();

    private CurrentRolesHolder() {}

    /** Never null — an unauthenticated/unset context reads as no roles. */
    public static Set<String> get() {
        Set<String> r = ROLES.get();
        return r != null ? r : Set.of();
    }

    public static void set(Set<String> roles) {
        if (roles == null) ROLES.remove(); else ROLES.set(Set.copyOf(roles));
    }

    public static void clear() { ROLES.remove(); }
}
