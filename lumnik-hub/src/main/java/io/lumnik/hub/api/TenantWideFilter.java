package io.lumnik.hub.api;

import io.lumnik.common.security.ScopeGrants;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * A caller bound to {@code scope:*} roles is answered the uniform {@code 403 scope not granted} on a
 * {@link TenantWide} door. Runs after {@code TenantContextFilter} ({@code AUTHORIZATION}), which
 * reads the caller's roles; an unbound caller passes on to the door's own role gate.
 */
@Provider
@TenantWide
@Priority(Priorities.AUTHORIZATION + 100)
@ApplicationScoped
public class TenantWideFilter implements ContainerRequestFilter {

  @Override
  public void filter(ContainerRequestContext ctx) {
    if (ScopeGrants.granted().isPresent()) {
      ctx.abortWith(ApiErrors.scopeNotGranted());
    }
  }
}
