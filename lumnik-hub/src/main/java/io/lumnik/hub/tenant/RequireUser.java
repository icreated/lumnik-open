package io.lumnik.hub.tenant;

import io.lumnik.hub.api.ApiErrors;
import jakarta.ws.rs.WebApplicationException;

/**
 * The authenticated user, or 401 — the twin of {@link RequireTenant}, and for the same reason: five
 * doors read the caller's id from {@code @HeaderParam("X-User-Id")}, so an authenticated caller
 * could act as someone else, and {@code UserScopeResource}'s PUT guarded null while its GET did not.
 * A guard that lives in each method is a guard one method forgets.
 *
 * <p>The plumbing differs from its twin on purpose: the tenant has a cross-module holder
 * ({@code CurrentTenantHolder}) because {@code lumnik-rag} reads it and cannot see this package.
 * Nothing outside {@code lumnik-hub} reads the user, so it stays on the filter's own thread-local.
 *
 * <p>Where a null user is legitimate — audit attribution on a system-triggered path — call
 * {@link TenantContextFilter#currentUser()} directly and say what null means there.
 */
public final class RequireUser {

    private RequireUser() {}

    public static long current() {
        Long u = TenantContextFilter.currentUser();
        if (u == null) throw new WebApplicationException(ApiErrors.unauthorized("no user in security context"));
        return u;
    }
}
