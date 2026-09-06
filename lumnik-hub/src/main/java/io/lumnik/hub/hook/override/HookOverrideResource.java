package io.lumnik.hub.hook.override;

import io.lumnik.hub.api.ApiErrors;
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
 * Per-tenant hook overrides, for the caller's OWN tenant.
 *
 * <p>Every door here takes the tenant as a parameter and every door now binds it to the caller.
 * That is not belt-and-braces: until this file switched tenants through {@code TenantScope}, the
 * switch was inert twice over — it set {@code TenantContextFilter.CURRENT_TENANT}, which
 * Hibernate's {@code tenantFilter} had already read BY VALUE at request-filter time, and never
 * {@code CurrentTenantHolder}, which is what {@code TenantConnectionInterceptor} reads to bind
 * the RLS GUC. A foreign read came back empty and a foreign write failed the policy's implicit
 * WITH CHECK. Making the switch work removed both of those accidental confinements at once, and
 * an override stores a Kotlin script the hub later EXECUTES — so a working switch without a gate
 * turns a tenant admin into code execution in another tenant's hub.
 *
 * <p>{@code @RequiresPermission("lm_admin")} does not close it: {@code PermissionInterceptor}
 * tests role-set membership and reads no tenant at all, and {@code lm_admin} is a tenant-level
 * role. The cross-tenant role in this product is {@code lm_superadmin}
 * ({@code TenantListResource}, {@code ImpersonationResource}). If cross-tenant hook
 * administration is wanted it needs that role and a door of its own; it is not something a
 * query parameter should decide.
 */
@Path("/api/platform/hooks/overrides")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@RequiresPermission("lm_admin")
public class HookOverrideResource {

    @Inject HookOverrideStore store;
    @Inject HookTenantOverrideRepository repo;

    public record UpsertRequest(String hookId, Long tenantId, String script) {}

    @GET
    @Transactional
    public List<HookOverrideDto> list(@QueryParam("tenantId") Long tenantId) {
        if (tenantId == null) throw new BadRequestException("tenantId query param required");
        Require.callerOwnsTenant(tenantId);
        return TenantScope.get(tenantId, () -> repo.findAllForTenant(tenantId).stream()
            .map(e -> new HookOverrideDto(e.getHookId(), e.getTenantId(), e.getScriptContent(), e.isEnabled()))
            .toList());
    }

    @GET @Path("/{hookId}")
    @Transactional
    public Response get(@PathParam("hookId") String hookId, @QueryParam("tenantId") Long tenantId) {
        if (tenantId == null) throw new BadRequestException("tenantId required");
        Require.callerOwnsTenant(tenantId);
        return TenantScope.get(tenantId, () -> repo.find(tenantId, hookId)
            .<Response>map(e -> Response.ok(new HookOverrideDto(
                e.getHookId(), e.getTenantId(), e.getScriptContent(), e.isEnabled())).build())
            .orElseGet(() -> ApiErrors.notFound("hook override for " + hookId + " not found")));
    }

    @POST @Consumes(MediaType.APPLICATION_JSON)
    public Response upsert(UpsertRequest req) {
        if (req.tenantId() == null) throw new BadRequestException("tenantId required");
        if (req.hookId() == null || req.hookId().isBlank()) throw new BadRequestException("hookId required");
        Require.callerOwnsTenant(req.tenantId());
        store.setScript(req.tenantId(), req.hookId(), req.script());
        return Response.noContent().build();
    }

    @DELETE @Path("/{hookId}")
    public Response delete(@PathParam("hookId") String hookId, @QueryParam("tenantId") Long tenantId) {
        if (tenantId == null) throw new BadRequestException("tenantId required");
        Require.callerOwnsTenant(tenantId);
        store.deleteOverride(tenantId, hookId);
        return Response.noContent().build();
    }

}
