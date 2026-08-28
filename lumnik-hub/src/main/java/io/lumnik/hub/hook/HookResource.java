package io.lumnik.hub.hook;

import io.lumnik.common.security.RequiresPermission;
import io.lumnik.hub.tenant.TenantContextFilter;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Path("/api/platform/hooks")
@Produces(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_admin")
public class HookResource {

    @Inject HookRegistry registry;
    @Inject HookOverrideStore overrideStore;

    public record ToggleRequest(boolean enabled) {}

    @GET
    public Map<String, Object> all() {
        return Map.of(
            "hooks",    registry.allBindings().stream().map(HookResource::summarize).toList(),
            "callouts", registry.allCallouts());
    }

    @GET @Path("/{id}")
    public Response one(@PathParam("id") String id) {
        Optional<HookBinding> b = registry.allBindings().stream()
            .filter(h -> h.id().equals(id)).findFirst();
        return b.<Response>map(v -> Response.ok(summarize(v)).build())
                .orElse(Response.status(404).build());
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
        boolean hookExists = registry.allBindings().stream().anyMatch(h -> h.id().equals(hookId));
        if (!hookExists) throw new NotFoundException("Unknown hook id: " + hookId);
        overrideStore.set(tenantId, hookId, body.enabled());
        return Response.noContent().build();
    }

    private static Map<String, Object> summarize(HookBinding b) {
        return Map.ofEntries(
            Map.entry("id",          b.id()),
            Map.entry("module",      b.module()),
            Map.entry("description", b.description() == null ? "" : b.description()),
            Map.entry("target",      b.targetClassName() == null ? "" : b.targetClassName()),
            Map.entry("action",      b.action() == null ? "" : b.action()),
            Map.entry("events",      b.events() == null ? Set.of() : b.events()),
            Map.entry("handler",     b.handler()),
            Map.entry("priority",    b.priority()),
            Map.entry("tenants",     b.tenants() == null ? List.of() : b.tenants()),
            Map.entry("enabled",     b.enabled()),
            Map.entry("actionPhase", b.actionPhase() == null ? "" : b.actionPhase().name())
        );
    }
}
