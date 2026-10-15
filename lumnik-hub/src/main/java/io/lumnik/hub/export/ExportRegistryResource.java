package io.lumnik.hub.export;

import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.tenant.RequireTenant;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Optional;
import java.util.Set;

/**
 * Scope- and tenant-bound reads over the export manifest store. Both methods carry
 * @Transactional even though they only read: SET LOCAL app.current_tenant is transaction-scoped
 * (TenantConnectionInterceptor fires it once per JTA transaction), so a read without one sees an
 * empty GUC, the RLS policy's NULLIF(...)::bigint guard turns that into NULL, and
 * tenant_id = NULL matches nothing — the exact defect SecretResourceRlsIT exists to catch for
 * platform.secret. Omitting @Transactional here would reproduce it for exports.
 */
@Path("/api/exports")
@Produces(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_integrator")
public class ExportRegistryResource {

    @Inject ExportManifestStore store;

    @GET
    @Transactional
    public Response list() {
        long tenant = RequireTenant.current();
        Set<String> granted = ScopeGrants.granted().orElse(null);
        return Response.ok(store.list(tenant, granted)).build();
    }

    @GET
    @Path("/{name}")
    @Produces({MediaType.APPLICATION_JSON, "application/yaml"})
    @Transactional
    public Response get(@PathParam("name") String name) {
        long tenant = RequireTenant.current();
        Optional<ExportManifestStore.Row> row = store.find(tenant, name);
        if (row.isEmpty()) {
            return ApiErrors.notFound("no export named '" + name + "'");
        }
        if (!ScopeGrants.allows(row.get().scope())) {
            return ApiErrors.scopeNotGranted();
        }
        return Response.ok(row.get().yaml(), "application/yaml").build();
    }
}
