package io.lumnik.hub.api;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;

/**
 * Auth gate for /api/platform/* endpoints.
 * Dev: bypassed via lumnik.security.dev-bypass=true.
 * Prod: requires role "lm_integrator" (RBAC catalog).
 *
 * <p>Both refusals declare {@code text/plain} EXPLICITLY. The documentation teaches readers to
 * tell three different 403s apart BY THEIR BODY — the body-less {@code @RequiresPermission} one,
 * the JSON {@code {"error":"scope not granted"}} one, and this plain sentence — and without the
 * type this one went out dressed as {@code application/json}, because an aborting filter's
 * response with no media type negotiates to what the RESOURCE declares, and every platform
 * resource declares JSON. A reader calling {@code response.json()} on it got a parse error, not a
 * refusal. Exactly the gap {@link ApiErrors#scopeNotGranted()} sets its own type against, on the
 * third sibling of those three; {@code PlatformAuthIT} now measures it (#517).
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
          .type(MediaType.TEXT_PLAIN)
          .entity("authentication required").build());
      return;
    }
    if (!sc.isUserInRole("lm_integrator")) {
      // Authenticated but not authorized for the platform API: 403.
      ctx.abortWith(Response.status(403).type(MediaType.TEXT_PLAIN)
          .entity("missing role lm_integrator").build());
      return;
    }
  }
}
