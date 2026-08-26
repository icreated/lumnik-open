package io.lumnik.hub.workflow;

import io.lumnik.common.security.RequiresPermission;
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

    @GET
    public List<WorkflowGraph> list() {
        return registry.all(RequireTenant.current());
    }

    /** The tape, flattened: what is not moving, oldest first. The literal segment outranks
     *  {@code /{name}}, so a workflow literally named "observations" cannot be fetched by
     *  name here — accepted and documented. Tenant comes from the request context, never a
     *  parameter (the store is admin-DS with explicit tenant binding). */
    @GET @Path("/observations")
    public List<WorkflowObservationStore.ObservationRow> observations(
            @QueryParam("limit") @DefaultValue("200") int limit) {
        return observationStore.listAll(RequireTenant.current(), Math.min(Math.max(limit, 1), 1000));
    }

    /** The tape, aggregated: the sponsor's numbers. Same auth and tenant posture as
     *  {@code /observations}; the literal segment outranks {@code /{name}} here too. */
    @GET @Path("/observations/stats")
    public List<WorkflowObservationStore.StatsRow> observationStats() {
        return observationStore.stats(RequireTenant.current());
    }

    @GET @Path("/{name}")
    public Response one(@PathParam("name") String name) {
        return registry.findByName(RequireTenant.current(), name)
            .<Response>map(g -> Response.ok(g).build())
            .orElse(Response.status(404).build());
    }
}
