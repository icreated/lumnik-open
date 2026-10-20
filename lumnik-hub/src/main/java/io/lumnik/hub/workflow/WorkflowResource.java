package io.lumnik.hub.workflow;

import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.tenant.RequireTenant;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/api/platform/workflows")
@Produces(MediaType.APPLICATION_JSON)
@RequiresPermission({"lm_admin", "lm_superadmin"})
public class WorkflowResource {

    @Inject WorkflowRegistry registry;

    @Inject WorkflowObservationStore observationStore;

    @Inject WorkflowManifestStore manifests;

    /** Every read here is a métier's: a bound caller reads the workflows of the scopes it holds. */
    @GET
    public List<WorkflowGraph> list() {
        return registry.all(RequireTenant.current(), ScopeGrants::allows);
    }

    /** The tape, flattened: what is not moving, oldest first. The literal segment outranks
     *  {@code /{name}}, so a workflow literally named "observations" cannot be fetched by
     *  name here — accepted and documented. Tenant comes from the request context, never a
     *  parameter (the store is admin-DS with explicit tenant binding). */
    @GET @Path("/observations")
    public List<WorkflowObservationStore.ObservationRow> observations(
            @QueryParam("limit") @DefaultValue("200") int limit) {
        return observationStore.listAll(RequireTenant.current(), ScopeGrants.granted().orElse(null), Math.min(Math.max(limit, 1), 1000));
    }

    /** The tape, aggregated: the sponsor's numbers. Same auth and tenant posture as
     *  {@code /observations}; the literal segment outranks {@code /{name}} here too. */
    @GET @Path("/observations/stats")
    public List<WorkflowObservationStore.StatsRow> observationStats() {
        return observationStore.stats(RequireTenant.current(), ScopeGrants.granted().orElse(null));
    }

    /** Another métier's workflow is the uniform 403, as on {@code /api/workflows/{name}/manifest} —
     *  decided on the stored scope, before the parse, like {@link #list}. */
    @GET @Path("/{name}")
    public Response one(@PathParam("name") String name) {
        long tenant = RequireTenant.current();
        if (!manifests.findScope(tenant, name).map(ScopeGrants::allows).orElse(true)) {
            return ApiErrors.scopeNotGranted();
        }
        return registry.findByName(tenant, name)
            .<Response>map(g -> Response.ok(g).build())
            .orElseGet(() -> ApiErrors.notFound("workflow " + name + " not found"));
    }
}
