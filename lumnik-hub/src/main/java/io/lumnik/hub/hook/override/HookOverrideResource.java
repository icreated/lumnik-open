package io.lumnik.hub.hook.override;

import io.lumnik.hub.api.ApiErrors;
import io.lumnik.common.security.TenantWide;
import io.lumnik.hub.hook.HookOverrideStore;
import io.lumnik.hub.hook.HookTenantOverrideRepository;
import io.lumnik.common.security.RequiresPermission;
import io.lumnik.identity.Require;
import io.lumnik.hub.scheduler.TenantScope;
import jakarta.transaction.Transactional;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * Per-tenant hook overrides, for the caller's OWN tenant: whether a hook runs for that tenant.
 *
 * <p>An override carries no code (#2011). It used to store a Kotlin script the hub compiled and ran in its own JVM,
 * which a tenant-level {@code lm_admin} could write: the hub's environment, its disk and every tenant's data were in
 * reach. The POST that stored one is refused for good; a tenant switches a hook at {@code HookResource}'s toggle door.
 *
 * <p>Every door here takes the tenant as a parameter and binds it to the caller: a toggle written for another
 * tenant would switch that tenant's hooks off. {@code @RequiresPermission("lm_admin")} alone would not hold it —
 * {@code PermissionInterceptor} tests role-set membership and reads no tenant, and {@code lm_admin} is a tenant-level
 * role. The switch goes through {@code TenantScope}, so RLS reads the right tenant too. {@code @TenantWide}: a caller
 * bound to a scope is refused, since a hook runs for every scope of the tenant (#1992).
 */
@Path("/api/platform/hooks/overrides")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@RequiresPermission("lm_admin")
@TenantWide
public class HookOverrideResource {

    /** #2011: what an override no longer carries, and where its one remaining use lives. */
    static final String NO_CODE = "a hook override carries no code: the hub runs the operator's scripts only. "
        + "To switch a hook on or off for your tenant: POST /api/platform/hooks/{hookId}/toggle";

    @Inject HookOverrideStore store;
    @Inject HookTenantOverrideRepository repo;

    @GET
    @Transactional
    public List<HookOverrideDto> list(@QueryParam("tenantId") Long tenantId) {
        if (tenantId == null) throw new BadRequestException("tenantId query param required");
        Require.callerOwnsTenant(tenantId);
        return TenantScope.get(tenantId, () -> repo.findAllForTenant(tenantId).stream()
            .map(e -> new HookOverrideDto(e.getHookId(), e.getTenantId(), e.isEnabled()))
            .toList());
    }

    @GET @Path("/{hookId}")
    @Transactional
    public Response get(@PathParam("hookId") String hookId, @QueryParam("tenantId") Long tenantId) {
        if (tenantId == null) throw new BadRequestException("tenantId required");
        Require.callerOwnsTenant(tenantId);
        return TenantScope.get(tenantId, () -> repo.find(tenantId, hookId)
            .<Response>map(e -> Response.ok(new HookOverrideDto(e.getHookId(), e.getTenantId(), e.isEnabled())).build())
            .orElseGet(() -> ApiErrors.notFound("hook override for " + hookId + " not found")));
    }

    /**
     * Refused, whatever it carries (#2011): this door stored a Kotlin script the hub then ran in its JVM for the
     * tenant — the hub's environment, its disk, every tenant's data. A hook's code is the operator's; a tenant switches
     * a hook on or off, at the toggle door the refusal names.
     */
    @POST
    public Response upsert() {
        return ApiErrors.badRequest(NO_CODE);
    }

    @DELETE @Path("/{hookId}")
    public Response delete(@PathParam("hookId") String hookId, @QueryParam("tenantId") Long tenantId) {
        if (tenantId == null) throw new BadRequestException("tenantId required");
        Require.callerOwnsTenant(tenantId);
        store.deleteOverride(tenantId, hookId);
        return Response.noContent().build();
    }

}
