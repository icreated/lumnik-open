package io.lumnik.hub.api.connector;

import io.lumnik.common.security.ScopeGrants;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/platform/connectors/{id}/tags")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ConnectorTagsResource {

  @Inject ConnectorRepository repo;

  @GET
  @Transactional
  public Response get(@PathParam("id") UUID id) {
    var c = repo.findById(id);
    if (c == null) return ApiErrors.notFound("connector " + id + " not found");
    var tags = c.getTags() != null ? c.getTags() : List.<String>of();
    return Response.ok(tags).build();
  }

  @PUT
  @Transactional
  public Response set(@PathParam("id") UUID id, List<String> tags) {
    if (tags == null) tags = List.of();
    // The same rule the manifest declares under (ScopesValidator) and kind:Entity enforces —
    // this endpoint used to carry a third, stricter copy, so a scope `apply` accepted could be
    // refused by `scope set` one command later.
    for (String t : tags) {
      var problem = ScopeGrants.nameProblem(t);
      if (problem.isPresent()) {
        return Response.status(400).entity(Map.of("error", problem.get())).build();
      }
    }
    var c = repo.findById(id);
    if (c == null) return ApiErrors.notFound("connector " + id + " not found");
    c.setTags(tags);
    return Response.ok(tags).build();
  }
}
