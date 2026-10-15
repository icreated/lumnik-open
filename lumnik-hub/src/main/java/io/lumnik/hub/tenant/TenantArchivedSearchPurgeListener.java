package io.lumnik.hub.tenant;

import io.lumnik.hub.search.SearchIndexStore;
import io.lumnik.identity.tenant.TenantArchived;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * #701 trigger row 7 (design doc §2/§4): purges every SearchIndex a tenant owns when it is
 * archived. Reacts to {@link TenantArchived}, fired synchronously by
 * {@code TenantResource.deactivate} (identity module) <b>before</b> {@code Tenant.archive()} runs.
 *
 * <p><b>This is the one #701 listener that does NOT catch-and-log.</b> Every other new listener in
 * this ticket ({@code SearchProjectionRunHook}, {@code SearchIndexRevalidator}'s extension) is
 * best-effort: a failure is logged and repaired later, because there is always a later signal — a
 * next connector run, a next entity re-apply — that gives the sweep another chance to converge.
 * This listener has no such later signal: once {@code archive()} commits, the tenant row is
 * archived and nothing will ever re-fire {@link TenantArchived} for it. Swallowing a purge
 * failure here would silently orphan that tenant's SearchIndex declarations (and eventually its
 * documents) with no path to ever being purged.
 *
 * <p>So this method is deliberately allowed to throw. A CDI {@code Event<T>.fire()} is
 * synchronous: it invokes every {@code @Observes} listener in-thread and re-throws the first
 * unchecked exception a listener raises, straight back to the firer — Weld does not wrap or
 * swallow it. That propagates out of {@code TenantResource.deactivate}, which is
 * {@code @Transactional}: the JAX-RS/Arjuna interceptor marks the transaction rollback-only on the
 * uncaught exception, so {@code t.archive()} — called only after {@code fire()} returns normally
 * — is never reached. No window exists where a tenant is archived without its purge queued.
 *
 * <p><b>The reverse window — purge committed, then {@code t.archive()} itself fails — is not
 * closed, and cannot be without cross-datasource XA</b> (identity's own JTA transaction and this
 * store's admin datasource, deliberately JTA-disabled per CLAUDE.md, are separate connection
 * pools). {@code purgeAllForTenant} already committed by the time {@code fire()} returns (the
 * admin datasource is never enlisted in the caller's transaction), so a later, unrelated failure
 * of {@code t.archive()}'s own JTA commit leaves the tenant still active with its SearchIndex
 * declarations already gone. This is accepted, documented compensating semantics (a review of
 * this PR, #709, named it explicitly) rather than fixed, because {@code purgeAllForTenant}'s CTE
 * only touches rows still present — retrying the same {@code DELETE /api/tenants/{id}} call
 * purges nothing a second time and simply completes the archive. See
 * {@code SearchIndexTenantPurgeIT}'s regression covering this exact recovery path.
 *
 * <p>{@code actor} is {@code null}: this call path originates from identity's
 * {@code lm_superadmin}-gated door, which runs outside any hub request — there is no
 * {@code TenantContextFilter}-populated hub user to attribute the purge to, the same reason
 * {@link io.lumnik.hub.search.SearchIndexRevalidator}'s cross-tenant sweep carries no per-row actor
 * either.
 */
@ApplicationScoped
public class TenantArchivedSearchPurgeListener {

    @Inject SearchIndexStore store;

    void onTenantArchived(@Observes TenantArchived event) {
        store.purgeAllForTenant(event.tenantId(), null);
    }
}
