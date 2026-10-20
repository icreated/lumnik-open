package io.lumnik.identity;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * The {@code {tenantId}} of a {@link TenantInPath} door must be the caller's own:
 * {@link Require#callerOwnsTenant}, with its refusal and its null-caller rule, ahead of the door.
 * Runs after the hub's {@code TenantContextFilter} ({@code AUTHORIZATION}), which binds the caller.
 *
 * <p>A segment that is not a number passes on: the door's {@code Long} parameter then fails to
 * convert, and that stays the 404 it was before this filter existed.
 */
@Provider
@TenantInPath
@Priority(Priorities.AUTHORIZATION + 100)
@ApplicationScoped
public class TenantInPathFilter implements ContainerRequestFilter {

    @Override
    public void filter(ContainerRequestContext ctx) {
        String raw = ctx.getUriInfo().getPathParameters().getFirst("tenantId");
        if (raw == null) {
            // A marked door whose path names the tenant otherwise ({tid}, a rename): the marker would
            // read as a guarantee while nothing is checked. A defect of the resource, said loudly.
            throw new IllegalStateException("@TenantInPath on a door whose path has no {tenantId}: "
                + ctx.getUriInfo().getPath());
        }
        try {
            Require.callerOwnsTenant(Long.parseLong(raw));
        } catch (NumberFormatException notATenant) {
            // left to the door, whose Long parameter answers 404
        }
    }
}
