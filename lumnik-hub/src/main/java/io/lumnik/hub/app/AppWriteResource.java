package io.lumnik.hub.app;

import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.audit.AuditRecorder;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The integrator's delete door for the app registry — {@code lm_integrator}, the inverse of
 * {@link AppApplyResource}. Kept off {@link AppRegistryResource} on purpose, the
 * {@code EntityWriteResource}/{@code EntityReadResource} split: that class stays {@code lm_user}
 * reads, this one is a write.
 */
@Path("/api/apps")
@Produces(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_integrator")
public class AppWriteResource {

    @Inject AppRegistrationStore store;
    @Inject AuditRecorder audit;

    /**
     * Removes one app for good. The 404/403 pre-check runs BEFORE the store's atomic re-ask, the
     * same ordering {@code AppApplyResource} uses for its own scope checks: a bound integrator
     * must not learn a scoped app exists — or that it currently holds the default flag — from the
     * refusal's shape. The store's {@code DELETE} carries the same two predicates in its own
     * {@code WHERE}, so the pre-check's read-then-act window is backed by an atomic re-ask, the
     * {@code AppLastDefaultFloorIT} reasoning applied to delete.
     */
    @DELETE
    @Path("/{name}")
    public Response delete(@PathParam("name") String name) {
        String key = name.strip().toLowerCase(Locale.ROOT);

        Response refusal = refuseUnlessHeld(key);
        if (refusal != null) return refusal;

        AppRegistrationStore.Deleted outcome = store.delete(key, ScopeGrants.granted().orElse(null));
        Response asRefusal = switch (outcome) {
            case AppRegistrationStore.Deleted.Removed ignored -> null;
            case AppRegistrationStore.Deleted.NotFound ignored -> notFound(key);
            case AppRegistrationStore.Deleted.HeldOutOfScope ignored -> ApiErrors.scopeNotGranted();
            case AppRegistrationStore.Deleted.IsTheLastDefault ignored ->
                ApiErrors.badRequest(AppApplyResource.LAST_DEFAULT_REFUSAL);
        };
        if (asRefusal != null) return asRefusal;

        audit.record("AppRegistration", key, "DELETE", Map.of());
        return Response.noContent().build();
    }

    /**
     * 404 when no app carries this name, 403 when the caller's scopes exclude it, 400 when it
     * currently carries the default flag, else null. The row is already fetched for its scope, so
     * reading {@code isDefault()} off the same row costs nothing extra — and answering here, ahead
     * of {@link AppRegistrationStore#delete}, means an ORDINARY last-default request never pays for
     * a {@code DELETE} doomed to touch zero rows and the disambiguating read that follows it.
     */
    private Response refuseUnlessHeld(String name) {
        Optional<AppRegistrationStore.Row> row = store.find(name);
        if (row.isEmpty()) return notFound(name);
        if (!ScopeGrants.allows(row.get().scope())) return ApiErrors.scopeNotGranted();
        if (row.get().isDefault()) return ApiErrors.badRequest(AppApplyResource.LAST_DEFAULT_REFUSAL);
        return null;
    }

    private static Response notFound(String name) {
        return ApiErrors.notFound("no app named '" + name + "'");
    }
}
