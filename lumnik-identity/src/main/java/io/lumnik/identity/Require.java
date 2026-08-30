package io.lumnik.identity;

import io.lumnik.common.domain.TenantedEntity;
import io.lumnik.common.tenant.CurrentTenantHolder;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;

/**
 * The path guard shared by every identity resource: resolve by id, or 404 — and for a tenanted
 * aggregate, a foreign tenant is indistinguishable from absence (same message, same status), so a
 * caller can never probe another tenant's id space.
 */
public final class Require {

    private Require() {}

    /** The row must exist. */
    public static <T> T found(PanacheRepository<T> repo, Long id, String label) {
        return repo.findByIdOptional(id).orElseThrow(() -> notFound(label, id));
    }

    /** The row must exist AND belong to {@code tenantId}; a mismatch 404s exactly like absence. */
    public static <T extends TenantedEntity> T owned(PanacheRepository<T> repo, Long id,
                                                     Long tenantId, String label) {
        T entity = found(repo, id, label);
        if (!entity.getTenantId().equals(tenantId)) throw notFound(label, id);
        return entity;
    }

    /**
     * The tenant NAMED IN THE URL must be the caller's own.
     *
     * <p>{@code /api/tenants/{tenantId}/…} carries a tenant in the path, and nothing compared it to
     * the one in the token. Nothing leaked — RLS scopes every tenanted table to
     * {@code app.current_tenant} — but the path parameter was not a filter, it was a fiction:
     * {@code UserResource.listAll} checks only that the tenant EXISTS and then runs a query with no
     * tenant predicate, so an admin of tenant 2 asking for tenant 1's users received their OWN,
     * with a 200, under someone else's URL. A write got further still and failed as a database
     * error, because the RLS policy's WITH CHECK — not the application — was what said no.
     *
     * <p>Called BEFORE any lookup, so an unknown tenant and one the caller does not own answer
     * alike; otherwise the surface doubles as a directory of which tenants exist.
     *
     * <p><b>No caller tenant means no request:</b> {@code TenantContextFilter.bindTenant} aborts
     * with 401 when the token carries no {@code tenant_id}, so on the request path this is never
     * null. Null means a background or in-process caller, where RLS remains the confinement — which
     * is also why the identity module's own HTTP tests, which mint no token, are unaffected.
     */
    public static void callerOwnsTenant(Long urlTenantId) {
        Long caller = CurrentTenantHolder.get();
        if (caller == null) return;
        if (!caller.equals(urlTenantId)) {
            throw new ForbiddenException("tenant not granted");
        }
    }

    private static NotFoundException notFound(String label, Long id) {
        return new NotFoundException(label + " not found: " + id);
    }
}
