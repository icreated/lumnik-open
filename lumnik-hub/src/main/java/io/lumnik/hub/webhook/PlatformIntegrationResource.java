package io.lumnik.hub.webhook;

import io.lumnik.common.security.RequiresPermission;
import io.lumnik.hub.tenant.RequireTenant;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * The lm-callable integrations family: tenant from the request context, never a URL
 * parameter — lm carries no numeric tenant against a real hub, the reason
 * {@link PlatformDeliveryResource} exists. Pure delegation to {@link IntegrationResource}:
 * one logic, two doors; the delegate's own {@code requireTenant(tid)} guard is satisfied
 * by construction since the tid passed IS the context tenant. The delegate's class-level
 * {@code @RequiresPermission("lm_admin")} fires again on the injected call — so this door
 * advertises exactly that role and no more: an annotation promising lm_superadmin here
 * would be refused one layer down.
 */
@Path("/api/platform/integrations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_admin")
@ApplicationScoped
public class PlatformIntegrationResource {

    @Inject IntegrationResource delegate;

    @GET
    public List<IntegrationDto> list() {
        return delegate.list(RequireTenant.current());
    }

    @GET @Path("/{id}")
    public IntegrationDto one(@PathParam("id") Long id) {
        return delegate.one(RequireTenant.current(), id);
    }

    @POST
    public Response create(IntegrationCreate in) {
        return delegate.create(RequireTenant.current(), in);
    }

    @PUT @Path("/{id}")
    public IntegrationDto update(@PathParam("id") Long id, IntegrationUpdate in) {
        return delegate.update(RequireTenant.current(), id, in);
    }

    @DELETE @Path("/{id}")
    public Response delete(@PathParam("id") Long id) {
        return delegate.delete(RequireTenant.current(), id);
    }

    @POST @Path("/{id}/test")
    public IntegrationResource.TestResponse test(@PathParam("id") Long id,
                                                 IntegrationResource.TestRequest req) {
        return delegate.test(RequireTenant.current(), id, req);
    }

    @GET @Path("/{id}/deliveries")
    public List<IntegrationResource.DeliveryRow> deliveries(@PathParam("id") Long id,
            @QueryParam("limit") @DefaultValue("50") int limit,
            @QueryParam("status") String status) {
        return delegate.deliveries(RequireTenant.current(), id, limit, status);
    }

    @POST @Path("/{id}/deliveries/{did}/replay")
    public IntegrationResource.TestResponse replay(@PathParam("id") Long id,
                                                   @PathParam("did") Long did) {
        return delegate.replay(RequireTenant.current(), id, did);
    }
}
