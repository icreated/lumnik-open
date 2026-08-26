package io.lumnik.hub.bff.user;

import io.lumnik.identity.user.UserRepository;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@Path("/api/platform/users/me/scope")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserScopeResource {

  @Inject UserRepository users;

  @GET
  @Transactional
  public Map<String, Object> get(@HeaderParam("X-User-Id") Long userId) {
    var u = users.findById(userId);
    Map<String, Object> body = new java.util.HashMap<>();
    body.put("defaultScope", u != null ? u.getDefaultScope() : null);
    return body;
  }

  @PUT
  @Transactional
  public Response set(@HeaderParam("X-User-Id") Long userId, Map<String, String> body) {
    if (userId == null) return Response.status(400).build();
    var u = users.findById(userId);
    if (u == null) return Response.status(404).build();
    String value = body != null ? body.get("defaultScope") : null;
    u.setDefaultScope(value == null || value.isBlank() ? null : value);
    return Response.status(204).build();
  }
}
