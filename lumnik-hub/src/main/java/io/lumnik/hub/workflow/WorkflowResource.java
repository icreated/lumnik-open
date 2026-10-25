package io.lumnik.hub.workflow;

import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.tenant.RequireTenant;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.util.List;

@Path("/api/platform/workflows")
@Produces(MediaType.APPLICATION_JSON)
@RequiresPermission({"lm_admin", "lm_superadmin"})
public class WorkflowResource {

    @Inject WorkflowRegistry registry;

    @Inject WorkflowObservationStore observationStore;

    @Inject WorkflowManifestStore manifests;

    /** Every read here is a scope's: a bound caller reads the workflows of the scopes it holds. */
    @GET
    public List<WorkflowGraph> list() {
        return registry.all(RequireTenant.current(), ScopeGrants::allows);
    }

    /** The tape, flattened: what is not moving, oldest first. The literal segment outranks
     *  {@code /{name}}, so a workflow literally named "observations" cannot be fetched by
     *  name here — accepted and documented. Tenant comes from the request context, never a
     *  parameter (the store is admin-DS with explicit tenant binding). {@code workflow=} narrows
     *  the query, not the page (#1361); another scope's name is the refusal {@link #one} gives. */
    @GET @Path("/observations")
    public Response observations(@QueryParam("limit") @DefaultValue("200") int limit, @Context UriInfo uri) {
        long tenant = RequireTenant.current();
        String workflow = ApiErrors.orBadRequest(() -> ApiErrors.once(uri.getQueryParameters(), "workflow"));
        if (workflow != null && !held(tenant, workflow)) {
            return ApiErrors.scopeNotGranted();
        }
        return Response.ok(observationStore.listAll(tenant, ScopeGrants.granted().orElse(null), workflow,
            Math.min(Math.max(limit, 1), 1000))).build();
    }

    /** The transition journal (#1368), newest first: each move and when its document entered the state
     *  it left. Same posture as {@code /observations}; {@code doc=} narrows to one document. */
    @GET @Path("/observations/transitions")
    public Response transitions(@QueryParam("limit") @DefaultValue("200") int limit, @Context UriInfo uri) {
        long tenant = RequireTenant.current();
        String workflow = ApiErrors.orBadRequest(() -> ApiErrors.once(uri.getQueryParameters(), "workflow"));
        String doc = ApiErrors.orBadRequest(() -> ApiErrors.once(uri.getQueryParameters(), "doc"));
        if (workflow != null && !held(tenant, workflow)) {
            return ApiErrors.scopeNotGranted();
        }
        return Response.ok(observationStore.transitions(tenant, ScopeGrants.granted().orElse(null), workflow, doc,
            Math.min(Math.max(limit, 1), 1000))).build();
    }

    /** The tape, aggregated: the sponsor's numbers. Same auth and tenant posture as
     *  {@code /observations}; the literal segment outranks {@code /{name}} here too. */
    @GET @Path("/observations/stats")
    public List<WorkflowObservationStore.StatsRow> observationStats() {
        return observationStore.stats(RequireTenant.current(), ScopeGrants.granted().orElse(null));
    }

    /** Another scope's workflow is the uniform 403, as on {@code /api/workflows/{name}/manifest} —
     *  decided on the stored scope, before the parse, like {@link #list}. */
    @GET @Path("/{name}")
    public Response one(@PathParam("name") String name) {
        long tenant = RequireTenant.current();
        if (!held(tenant, name)) {
            return ApiErrors.scopeNotGranted();
        }
        return registry.findByName(tenant, name)
            .<Response>map(g -> Response.ok(g).build())
            .orElseGet(() -> ApiErrors.notFound("workflow " + name + " not found"));
    }

    /** Whether the caller's scopes reach this workflow; an unknown name is not refused here — it is a 404 or an empty tape. */
    private boolean held(long tenant, String name) {
        return manifests.findScope(tenant, name).map(ScopeGrants::allows).orElse(true);
    }
}
