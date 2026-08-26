package io.lumnik.common.tenant;

public final class CurrentTenantHolder {

    private static final ThreadLocal<Long> TENANT = new ThreadLocal<>();

    private CurrentTenantHolder() {}

    public static Long get() { return TENANT.get(); }

    public static void set(Long tenantId) {
        if (tenantId == null) TENANT.remove(); else TENANT.set(tenantId);
    }

    public static void clear() { TENANT.remove(); }
}
