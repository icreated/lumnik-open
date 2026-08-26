package io.lumnik.hub.api.connector;

import io.lumnik.hub.connector.repository.ConnectorRepository;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Path("/api/platform/connectors/{id}/tags")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ConnectorTagsResource {

  private static final Pattern TAG = Pattern.compile("^[a-z][a-z0-9_-]{0,40}$");

  @Inject ConnectorRepository repo;

  @GET
  @Transactional
  public Response get(@PathParam("id") UUID id) {
    var c = repo.findById(id);
    if (c == null) return Response.status(404).build();
    var tags = c.getTags() != null ? c.getTags() : List.<String>of();
    return Response.ok(tags).build();
  }

  @PUT
  @Transactional
  public Response set(@PathParam("id") UUID id, List<String> tags) {
    if (tags == null) tags = List.of();
    for (String t : tags) {
      if (t == null || !TAG.matcher(t).matches()) {
        return Response.status(400)
            .entity(Map.of(
                "error", "invalid tag: " + t,
                "expected", "^[a-z][a-z0-9_-]{0,40}$"))
            .build();
      }
    }
    var c = repo.findById(id);
    if (c == null) return Response.status(404).build();
    c.setTags(tags);
    return Response.ok(tags).build();
  }
}
