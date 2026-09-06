package io.lumnik.hub.scheduler;

import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.hub.tenant.TenantContextFilter;
import org.jboss.logging.MDC;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Utility for scheduler jobs that need to run work per-tenant.
 *
 * Pattern:
 *   for (Long tenantId : activeTenantIds) {
 *       TenantScope.run(tenantId, () -> doWorkForTenant(tenantId));
 *   }
 *
 * Sets both TenantContextFilter (Hibernate filter) and CurrentTenantHolder
 * (RLS GUC via TenantConnectionInterceptor) so all data access is properly
 * scoped to the tenant, then restores the previous state.
 */
public final class TenantScope {

    private static final String MDC_TENANT_ID = "tenantId";

    private TenantScope() {}

    public static void run(Long tenantId, Runnable work) {
        Long previous = TenantContextFilter.current();
        try {
            TenantContextFilter.CURRENT_TENANT.set(tenantId);
            CurrentTenantHolder.set(tenantId);
            MDC.put(MDC_TENANT_ID,tenantId);
            work.run();
        } finally {
            restore(previous);
        }
    }

    /**
     * {@link #call} for work that throws nothing — the shape a read has.
     *
     * <p>Both ThreadLocals, which is the whole point of coming here instead of setting one by
     * hand. {@code TenantContextFilter.CURRENT_TENANT} is what Hibernate's {@code tenantFilter}
     * reads; {@code CurrentTenantHolder} is what {@code TenantConnectionInterceptor} reads to
     * bind the GUC. Setting only the first works right up until the caller is
     * {@code @Transactional} — where the Hibernate filter is not enabled at all — and then the
     * switch is inert and the query silently answers for the CALLER's tenant instead.
     */
    public static <T> T get(Long tenantId, Supplier<T> work) {
        Long previous = TenantContextFilter.current();
        try {
            TenantContextFilter.CURRENT_TENANT.set(tenantId);
            CurrentTenantHolder.set(tenantId);
            MDC.put(MDC_TENANT_ID, tenantId);
            return work.get();
        } finally {
            restore(previous);
        }
    }

    public static <T> T call(Long tenantId, Callable<T> work) throws Exception {
        Long previous = TenantContextFilter.current();
        try {
            TenantContextFilter.CURRENT_TENANT.set(tenantId);
            CurrentTenantHolder.set(tenantId);
            MDC.put(MDC_TENANT_ID,tenantId);
            return work.call();
        } finally {
            restore(previous);
        }
    }

    private static void restore(Long previous) {
        if (previous != null) {
            TenantContextFilter.CURRENT_TENANT.set(previous);
            CurrentTenantHolder.set(previous);
            MDC.put(MDC_TENANT_ID,previous);
        } else {
            TenantContextFilter.CURRENT_TENANT.remove();
            CurrentTenantHolder.clear();
            MDC.remove(MDC_TENANT_ID);
        }
    }
}
