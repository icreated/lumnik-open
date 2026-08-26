package io.lumnik.hub.api;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;

/**
 * Auth gate for /api/platform/* endpoints.
 * Dev: bypassed via lumnik.security.dev-bypass=true.
 * Prod: requires role "lm_integrator" (RBAC catalog).
 */
@Provider
@Priority(Priorities.AUTHENTICATION + 100)
@ApplicationScoped
public class PlatformApiAuthFilter implements ContainerRequestFilter {

  @ConfigProperty(name = "lumnik.security.dev-bypass", defaultValue = "false")
  boolean devBypass;

  @Override
  public void filter(ContainerRequestContext ctx) throws IOException {
    String path = ctx.getUriInfo().getPath();
    if (!path.startsWith("api/platform/") && !path.startsWith("/api/platform/")) {
      return;
    }
    if (devBypass) return;
    var sc = ctx.getSecurityContext();
    if (sc == null || sc.getUserPrincipal() == null) {
      // No authenticated principal: 401 with a Bearer challenge (RFC 7235 §3.1).
      ctx.abortWith(Response.status(401)
          .header("WWW-Authenticate", "Bearer")
          .entity("authentication required").build());
      return;
    }
    if (!sc.isUserInRole("lm_integrator")) {
      // Authenticated but not authorized for the platform API: 403.
      ctx.abortWith(Response.status(403).entity("missing role lm_integrator").build());
      return;
    }
  }
}
