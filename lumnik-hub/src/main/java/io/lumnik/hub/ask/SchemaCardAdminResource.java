package io.lumnik.hub.ask;

import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.extraction.spi.ask.DiscoveryResult;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.tenant.RequireTenant;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;


/**
 * The schema card's admin surface: read one scope's card, or rebuild it from the registry.
 *
 * <p><b>Why it lives in the hub.</b> It used to be {@code RagSchemaResource} in {@code lumnik-rag},
 * which the open edition does not ship — while every page of the app ladder (Entities, Views,
 * Processes, Workflows) is headed "Edition: Open" and every one of them tells the reader to run
 * {@code lm source rediscover --scope <tag>} when a card is missing. The open edition therefore
 * documented a gesture it did not have (#488). The card itself was never a rag concept: it is
 * built by {@code RegistrySchemaBuilder} and {@code EntitySchemaBuilder}, stored by
 * {@code SchemaCardStore} and served by {@code DiscoveryService} — all of them hub classes. Only
 * the door was on the wrong side of the seam.
 *
 * <p>The paths are unchanged on purpose, {@code rag} prefix and all: {@code lm}, the docs and the
 * OTK corpus address them, and a rename would break every one of those to gain a tidier URL. The
 * word is legacy; the surface is the hub's.
 *
 * <p>{@code DiscoveryPort} is implemented here, in this module, so there is no absent-extension
 * branch left to write — the {@code Instance<>} guard and its 503 went with the move.
 *
 * <p><b>The move closed a hole.</b> Every door working inside a métier scope must decide whether
 * the caller holds it, and {@code ScopeBoundaryInventoryTest} makes forgetting fail the build —
 * but it walks the HUB's resources, so this one, sitting in {@code lumnik-rag}, was outside its
 * sweep and had never asked. A scope-bound admin could read, or rebuild, the card of a scope they
 * do not hold: the tables, the columns, the labels. It now answers the uniform refusal, like every
 * kind:View, kind:Process and kind:Entity door. An unscoped admin is unaffected — no
 * {@code scope:*} role means tenant-wide.
 */
@Path("/api/platform/rag")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_admin")
public class SchemaCardAdminResource {

    @Inject DiscoveryService discovery;

    @GET
    @Path("/schema")
    public Response getSchema(@QueryParam("scope") String raw) {
        long tenant = RequireTenant.current();
        String scope = normalized(raw);
        Response refusal = refuseUnlessHeld(scope);
        if (refusal != null) return refusal;
        return discovery.load(tenant, scope).<Response>map(c -> Response.ok(c).build())
            .orElseGet(() -> ApiErrors.notFound("No schema card found for scope: " + scope));
    }

    @POST
    @Path("/rediscover")
    @Transactional
    public Response rediscover(@QueryParam("scope") String raw) {
        long tenant = RequireTenant.current();
        String scope = normalized(raw);
        Response refusal = refuseUnlessHeld(scope);
        if (refusal != null) return refusal;
        DiscoveryResult result = discovery.rediscover(tenant, scope);
        return Response.ok(result).build();
    }

    /**
     * The scope as every other scope-aware door reads it: stripped, once, before it is used for
     * ANYTHING. `?scope=clients%20` used to be a different scope name than `clients` — refused for
     * a bound caller with a message naming a tag they do hold, and, for an unscoped one, looked up
     * under a key no card is stored beneath. `SavedViewsResource` and `ViewGenerateResource` both
     * strip; this door did not, and carried that from the resource it replaces (raised on #510).
     */
    private static String normalized(String raw) {
        return raw == null ? null : raw.strip();
    }

    /**
     * 400 for a missing scope, 403 for one the caller does not hold, else null. Both doors ask the
     * same question in the same order, so neither can drift from the other — which is the whole
     * reason this class's move mattered. Called AFTER {@code RequireTenant.current()}: a 400 on a
     * missing parameter must not be answerable by someone the 401 has not yet turned away.
     */
    private static Response refuseUnlessHeld(String scope) {
        if (scope == null || scope.isBlank()) return ApiErrors.badRequest("scope is required");
        return ScopeGrants.allows(scope) ? null : ApiErrors.scopeNotGranted();
    }
}
