package io.lumnik.hub.hook.override;

import io.lumnik.hub.hook.HookOverrideStore;
import io.lumnik.hub.hook.HookTenantOverrideRepository;
import io.lumnik.common.security.RequiresPermission;
import io.lumnik.hub.tenant.TenantContextFilter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/api/platform/hooks/overrides")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@RequiresPermission("lm_admin")
public class HookOverrideResource {

    @Inject HookOverrideStore store;
    @Inject HookTenantOverrideRepository repo;

    public record UpsertRequest(String hookId, Long tenantId, String script) {}

    @GET
    public List<HookOverrideDto> list(@QueryParam("tenantId") Long tenantId) {
        if (tenantId == null) throw new BadRequestException("tenantId query param required");
        Long prev = TenantContextFilter.CURRENT_TENANT.get();
        try {
            TenantContextFilter.CURRENT_TENANT.set(tenantId);
            return repo.findAllForTenant(tenantId).stream()
                .map(e -> new HookOverrideDto(e.getHookId(), e.getTenantId(), e.getScriptContent(), e.isEnabled()))
                .toList();
        } finally {
            restore(prev);
        }
    }

    @GET @Path("/{hookId}")
    public Response get(@PathParam("hookId") String hookId, @QueryParam("tenantId") Long tenantId) {
        if (tenantId == null) throw new BadRequestException("tenantId required");
        Long prev = TenantContextFilter.CURRENT_TENANT.get();
        try {
            TenantContextFilter.CURRENT_TENANT.set(tenantId);
            return repo.find(tenantId, hookId)
                .<Response>map(e -> Response.ok(new HookOverrideDto(
                    e.getHookId(), e.getTenantId(), e.getScriptContent(), e.isEnabled())).build())
                .orElse(Response.status(404).build());
        } finally {
            restore(prev);
        }
    }

    @POST @Consumes(MediaType.APPLICATION_JSON)
    public Response upsert(UpsertRequest req) {
        if (req.tenantId() == null) throw new BadRequestException("tenantId required");
        if (req.hookId() == null || req.hookId().isBlank()) throw new BadRequestException("hookId required");
        store.setScript(req.tenantId(), req.hookId(), req.script());
        return Response.noContent().build();
    }

    @DELETE @Path("/{hookId}")
    public Response delete(@PathParam("hookId") String hookId, @QueryParam("tenantId") Long tenantId) {
        if (tenantId == null) throw new BadRequestException("tenantId required");
        store.deleteOverride(tenantId, hookId);
        return Response.noContent().build();
    }

    private void restore(Long prev) {
        if (prev == null) TenantContextFilter.CURRENT_TENANT.remove();
        else TenantContextFilter.CURRENT_TENANT.set(prev);
    }
}
