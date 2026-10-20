package io.lumnik.hub.search;

import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.api.ApplyScopeBoundary;
import io.lumnik.hub.audit.AuditRecorder;
import io.lumnik.hub.tenant.RequireTenant;
import io.lumnik.hub.tenant.TenantContextFilter;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads and deletes of {@code kind: SearchIndex} (#669). Both carry {@code @Transactional}, the read
 * too: SET LOCAL app.current_tenant is transaction-scoped, and without it RLS answers an empty list.
 *
 * <p>The list is scope-bound by the entity each declaration indexes ({@code allowsAny}, the read
 * rule). DELETE is integrator-only and asks {@code allowsAll} over the stored entity's scopes, and
 * writes the purge obligation #701 consumes in the same statement as the delete itself.
 */
@Path("/api/search-indexes")
@Produces(MediaType.APPLICATION_JSON)
public class SearchIndexRegistryResource {

    @Inject SearchIndexStore store;
    @Inject AuditRecorder audit;

    @GET
    @RequiresPermission("lm_user")
    @Transactional
    public List<Map<String, Object>> list() {
        long tenant = RequireTenant.current();
        return store.list(tenant).stream()
            .filter(s -> ScopeGrants.allowsAny(s.entityScopes()))
            .map(SearchIndexRegistryResource::body)
            .toList();
    }

    @DELETE
    @Path("/{name}")
    @RequiresPermission("lm_integrator")
    @Transactional
    public Response delete(@PathParam("name") String name) {
        long tenant = RequireTenant.current();

        Optional<List<String>> stored = store.storedEntityScopes(tenant, name);
        if (stored.isEmpty()) return ApiErrors.notFound("no search index named '" + name + "'");
        // ORDERED — read, then write; deleteWithPurge re-asks the same guard in the DELETE's WHERE.
        ApplyScopeBoundary.refuseUnlessCallerHolds(stored);

        SearchIndexStore.DeleteOutcome outcome = store.deleteWithPurge(
            tenant, name, TenantContextFilter.currentUser(), ScopeGrants.granted().orElse(null));
        return switch (outcome) {
            case NOT_FOUND -> ApiErrors.notFound("no search index named '" + name + "'");
            case SCOPE_REFUSED -> ApiErrors.scopeNotGranted();
            case DELETED -> {
                audit.record("SearchIndex", name, "DELETE", Map.of("purge", "requested"));
                yield Response.noContent().build();
            }
        };
    }

    /** Every key always present, {@code invalidReason} null when valid; {@code withheld} until rebuilt (#1219). */
    private static Map<String, Object> body(SearchIndexStore.Summary s) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", s.name());
        body.put("entity", s.entity());
        body.put("language", s.language());
        body.put("status", s.status());
        body.put("invalidReason", s.invalidReason());
        body.put("withheld", s.withheld());
        return body;
    }
}
