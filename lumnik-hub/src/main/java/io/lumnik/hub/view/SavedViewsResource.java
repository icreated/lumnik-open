package io.lumnik.hub.view;

import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.hub.api.ApiErrors;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Optional;
import java.util.Set;

/**
 * Scope-bound reads over the saved-view store — how a renderer without a filesystem
 * (the PWA) discovers its views. Opt-in semantics (#131/#138): unscoped = all,
 * bound = the granted scopes only; the uniform 403 for anything outside them.
 */
@Path("/api/views")
// JSON + YAML at the class level so get()'s strict-Accept clients never 406 before
// invocation (the ViewGenerateResource precedent / the #133 shape) — each response
// sets its concrete type explicitly; list() narrows itself back to JSON.
@Produces({MediaType.APPLICATION_JSON, "application/yaml"})
@RequiresPermission("lm_user")
public class SavedViewsResource {

  @Inject SavedViewStore store;

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Response list(@QueryParam("scope") String scope) {
    Set<String> granted = ScopeGrants.granted().orElse(null);
    if (scope != null && !scope.isBlank()) {
      String s = scope.strip();
      if (!ScopeGrants.allows(s)) {
        return ApiErrors.scopeNotGranted();
      }
      return Response.ok(store.list(Set.of(s))).build();
    }
    return Response.ok(store.list(granted)).build();
  }

  @GET
  @Path("/{name}")
  public Response get(@PathParam("name") String name) {  // inherits JSON+YAML; every response sets type()
    Optional<SavedViewStore.Row> row = store.find(name);
    if (row.isEmpty()) {
      return Response.status(404).type(MediaType.APPLICATION_JSON)
          .entity(ApiErrors.error("no saved view named '" + name + "'")).build();
    }
    if (!ScopeGrants.allows(row.get().scope())) {
      return ApiErrors.scopeNotGranted();
    }
    return Response.ok(row.get().yaml(), "application/yaml").build();
  }
}
