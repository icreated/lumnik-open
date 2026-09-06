package io.lumnik.hub.bff.user;

import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.tenant.RequireUser;
import io.lumnik.identity.user.UserRepository;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Collections;
import java.util.Map;

@Path("/api/platform/users/me/scope")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserScopeResource {

  @Inject UserRepository users;

  @GET
  @Transactional
  public Response get() {
    long id = RequireUser.current();
    var u = users.findById(id);
    if (u == null) return noRow(id);
    return Response.ok(Collections.singletonMap("defaultScope", u.getDefaultScope())).build();
  }

  @PUT
  @Transactional
  public Response set(Map<String, String> body) {
    long id = RequireUser.current();
    var u = users.findById(id);
    if (u == null) return noRow(id);
    String value = body != null ? body.get("defaultScope") : null;
    u.setDefaultScope(value == null || value.isBlank() ? null : value);
    return Response.status(204).build();
  }

  /**
   * Legitimate under the user_id-claim path (the hub does not own every IdP's user table), but
   * this door needs the row — and "no row" must not read as "no scope set".
   */
  private static Response noRow(long id) {
    return ApiErrors.notFound("no user record on this hub for user_id " + id);
  }
}
