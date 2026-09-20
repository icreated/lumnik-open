package io.lumnik.hub.view;

import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.hub.api.ApiErrors;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
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
      return ApiErrors.notFound("no saved view named '" + name + "'");
    }
    if (!ScopeGrants.allows(row.get().scope())) {
      return ApiErrors.scopeNotGranted();
    }
    return Response.ok(row.get().yaml(), "application/yaml").build();
  }

  /**
   * Removes one saved view for good — the delete gesture #487 gave kind:Process, missing here
   * (#507). A mutation, so it does not ride the class's read-only {@code lm_user} default.
   * Scope-bound like {@link #get}: the 404 is answered from the stored scope BEFORE anything is
   * touched, so a bound user cannot learn that a view they may not hold exists by deleting it.
   *
   * <p>Uses {@code findScope} rather than {@link #get}'s {@code find}: a delete has no yaml to
   * return, so there is no reason to pull it off the row just to answer the boundary.
   */
  @DELETE
  @Path("/{name}")
  @RequiresPermission("lm_integrator")
  public Response delete(@PathParam("name") String name) {
    Optional<String> scope = store.findScope(name);
    if (scope.isEmpty()) {
      return ApiErrors.notFound("no saved view named '" + name + "'");
    }
    if (!ScopeGrants.allows(scope.get())) {
      return ApiErrors.scopeNotGranted();
    }
    store.delete(name);
    return Response.noContent().build();
  }
}
