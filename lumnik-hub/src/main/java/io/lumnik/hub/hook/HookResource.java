package io.lumnik.hub.hook;

import io.lumnik.common.security.RequiresPermission;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.common.security.TenantWide;
import io.lumnik.hub.tenant.RequireTenant;
import io.lumnik.hub.tenant.TenantContextFilter;
import io.lumnik.identity.Require;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Path("/api/platform/hooks")
@Produces(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_admin")
@TenantWide
public class HookResource {

    @Inject HookRegistry registry;
    @Inject HookOverrideStore overrideStore;

    public record ToggleRequest(boolean enabled) {}

    /** The caller's tenant's hooks only: another tenant's binding, and its tenant ids, are not this tenant's (#1992). */
    @GET
    public Map<String, Object> all() {
        long tenant = RequireTenant.current();
        return Map.of(
            "hooks",    registry.bindingsFor(tenant).stream().map(b -> summarize(b, tenant)).toList(),
            "callouts", registry.allCallouts());
    }

    @GET @Path("/{id}")
    public Response one(@PathParam("id") String id) {
        long tenant = RequireTenant.current();
        Optional<HookBinding> b = registry.bindingsFor(tenant).stream().filter(h -> h.id().equals(id)).findFirst();
        return b.<Response>map(v -> Response.ok(summarize(v, tenant)).build())
                .orElseGet(() -> ApiErrors.notFound("hook " + id + " not found"));
    }

    @POST @Path("/{hookId}/toggle")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response toggle(@PathParam("hookId") String hookId,
                           @QueryParam("tenantId") Long queryTenantId,
                           ToggleRequest body) {
        Long tenantId = queryTenantId != null
            ? queryTenantId
            : TenantContextFilter.CURRENT_TENANT.get();
        if (tenantId == null) throw new BadRequestException("Tenant required");
        // Same gate, same reason as HookOverrideResource: the switch this reaches is effective
        // now, and lm_admin says nothing about which tenant.
        Require.callerOwnsTenant(tenantId);
        // a hook of the tenant's own: another tenant's private hook is unknown here, as in the lists (#1992)
        boolean hookExists = registry.bindingsFor(tenantId).stream().anyMatch(h -> h.id().equals(hookId));
        if (!hookExists) throw new NotFoundException("Unknown hook id: " + hookId);
        overrideStore.set(tenantId, hookId, body.enabled());
        return Response.noContent().build();
    }

    private static Map<String, Object> summarize(HookBinding b, long tenant) {
        return Map.ofEntries(
            Map.entry("id",          b.id()),
            Map.entry("module",      b.module()),
            Map.entry("description", b.description() == null ? "" : b.description()),
            Map.entry("target",      b.targetClassName() == null ? "" : b.targetClassName()),
            Map.entry("action",      b.action() == null ? "" : b.action()),
            Map.entry("events",      b.events() == null ? Set.of() : b.events()),
            Map.entry("handler",     b.handler()),
            Map.entry("priority",    b.priority()),
            Map.entry("tenants",     b.tenantsAsSeenBy(tenant)),
            Map.entry("enabled",     b.enabled()),
            Map.entry("actionPhase", b.actionPhase() == null ? "" : b.actionPhase().name())
        );
    }
}
