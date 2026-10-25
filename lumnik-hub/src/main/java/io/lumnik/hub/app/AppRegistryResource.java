package io.lumnik.hub.app;

import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.hub.api.ApiErrors;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Optional;

/**
 * Scope-bound reads over the app registry — how the PWA learns its own identity. Opt-in semantics
 * (#131/#138): unscoped = all, bound = the granted scopes only.
 *
 * <p>Deliberately NOT public. The design that introduced this endpoint specified it unauthenticated
 * so the PWA could resolve before login; every pre-login use was then checked one by one and the
 * premise did not hold — {@code redirectUri} reads only the {@code ?m=} query parameter, and
 * {@code spec.scope} is consumed exclusively on the post-login screen. The only genuinely pre-token
 * need is the title and icon of the login screen, which the consumption half of this feature will
 * serve from its own cached registry rather than this door. A public door here would have cost a
 * skip in {@code TenantContextFilter} — a second auth matcher its own javadoc argues against — and
 * served the deployment's whole inventory of scope tags to anyone who asked.
 */
@Path("/api/apps")
@Produces(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_user")
public class AppRegistryResource {

    @Inject AppRegistrationStore store;

    @GET
    public Response list() {
        return Response.ok(store.list(ScopeGrants.granted().orElse(null))).build();
    }

    @GET
    @Path("/{name}")
    public Response get(@PathParam("name") String name) {
        Optional<AppRegistrationStore.Row> row = store.find(name);
        if (row.isEmpty()) {
            return ApiErrors.notFound("no app named '" + name + "'");
        }
        if (!ScopeGrants.allows(row.get().scope())) {
            return ApiErrors.scopeNotGranted();
        }
        return Response.ok(row.get()).build();
    }
}
