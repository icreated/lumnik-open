package io.lumnik.hub.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.api.ApplyDoorSupport;
import io.lumnik.hub.api.ApplyScopeBoundary;
import io.lumnik.hub.audit.AuditRecorder;
import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.entity.EntityManifestParser;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.search.projection.ProjectionDefinitions;
import io.lumnik.hub.tenant.RequireTenant;
import io.lumnik.hub.tenant.TenantContextFilter;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Curated writes of {@code kind: SearchIndex} (#669): validate, then upsert. Integrator-only, like
 * every other manifest apply door. Applying with no Search provider installed stores configuration
 * and starts no backend work.
 *
 * <p>Order, and why: parse → unknown keys → name → <b>the name's current entity</b> (a re-apply
 * cannot take over a declaration held over an entity outside the caller's grants) → the new entity
 * exists → <b>the caller holds every scope of it</b> → its canonical columns cover the fields →
 * guarded upsert → audit. Both scope checks run before the field cross-check on purpose: the entity
 * registry is deployment-global, and the cross-check's message names the entity's columns, which a
 * caller outside its scopes must not be able to probe (the {@code ExportApplyResource} reasoning).
 */
@Path("/api/search-indexes/apply")
@Produces(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_integrator")
public class SearchIndexApplyResource {

    static final Set<String> RESERVED_NAMES = Set.of("apply");

    @Inject SearchIndexStore store;
    @Inject EntityRegistry entities;
    @Inject EntityManifestParser entityParser;
    @Inject AuditRecorder audit;
    @Inject ObjectMapper json;

    private final SearchIndexManifestParser parser = new SearchIndexManifestParser();

    @POST
    @Consumes({"application/yaml", "text/yaml", MediaType.APPLICATION_JSON})
    @Transactional
    public Response apply(String body) {
        long tenant = RequireTenant.current();

        JsonNode root = ApiErrors.orBadRequest(() -> parser.readTree(body));
        Optional<String> named = parser.namedRefusal(root);
        if (named.isPresent()) return ApiErrors.badRequest(named.get());
        Optional<Response> unknownRefusal = ApplyDoorSupport.refuseUnknownKeys(
            "SearchIndex", parser.unknownKeys(root), SearchIndexManifestParser.honoredSurface());
        if (unknownRefusal.isPresent()) return unknownRefusal.get();
        SearchIndexManifest manifest = ApiErrors.orBadRequest(() -> parser.parse(root));

        // Kept as written (spec N): a name that is not already a URL-safe slug is refused, not folded.
        String name = manifest.name();
        Optional<Response> slugRefusal = ApplyDoorSupport.refuseReservedOrInvalidSlug(name, RESERVED_NAMES);
        if (slugRefusal.isPresent()) return slugRefusal.get();

        // ORDERED — read, then write; the store re-asks atomically in the upsert's own WHERE.
        ApplyScopeBoundary.refuseUnlessCallerHolds(store.storedEntityScopes(tenant, name));

        // The STORED definition, not EntityRegistry's cache: another instance may have re-applied the
        // entity into other scopes, or without a column, and a cache hit would still say otherwise.
        // The write below is bound to exactly this version.
        Optional<EntityRegistry.Stored> stored = entities.stored(manifest.entity());
        if (stored.isEmpty()) {
            return ApiErrors.badRequest("spec.entity: entity '" + manifest.entity() + "' not found");
        }
        EntityManifest entity = entityParser.parse(stored.get().yaml());
        if (!ScopeGrants.allowsAll(stored.get().scopes())) {
            return ApiErrors.scopeNotGranted();
        }
        Optional<String> problem = SearchIndexDefinitionCheck.problem(manifest, entity);
        if (problem.isPresent()) return ApiErrors.badRequest(problem.get());

        // entity.name(), not manifest.entity(): entity names resolve case-insensitively, and the
        // stored, listed and audited spelling must be the canonical one.
        SearchIndexManifest canonical = new SearchIndexManifest(
            name, entity.name(), manifest.language(), manifest.fields(), manifest.titleField(), manifest.links());
        SearchIndexStore.Applied applied = store.upsert(tenant, name, entity.name(), stored.get().yaml(),
            manifest.language(), body, toJson(canonical), ProjectionDefinitions.from(canonical), TenantContextFilter.currentUser(),
            ScopeGrants.granted().orElse(null));
        switch (applied.outcome()) {
            case APPLIED -> { }
            case NAME_HELD_OUT_OF_SCOPE -> { return ApiErrors.scopeNotGranted(); }
            case ENTITY_ALREADY_INDEXED -> {
                // The holder indexes THIS entity, whose scopes the caller was just checked to hold
                // (allowsAll above), so naming it tells them nothing outside their grants.
                String holder = applied.holder();
                return ApiErrors.conflict("entity '" + entity.name() + "' is already indexed by SearchIndex '"
                    + holder + "' — one SearchIndex per Entity: re-apply '" + holder + "' to change it (match"
                    + " modes can be mixed field by field), or delete it first");
            }
            case ENTITY_CHANGED -> {
                return ApiErrors.conflict("entity '" + entity.name()
                    + "' changed while this SearchIndex was being applied — apply it again");
            }
        }

        audit.record("SearchIndex", name, "APPLY", Map.of(
            "entity", entity.name(),
            "searchable", canonical.searchableFields(),
            "returnable", canonical.returnableFields(),
            "manifest_sha256", AuditRecorder.sha256(body)));

        return Response.ok(Map.of("name", name, "entity", entity.name(), "status", "valid")).build();
    }

    /** The parsed declaration #671 projects from; {@code match} is absent for a return-only field. */
    private String toJson(SearchIndexManifest m) {
        try {
            return json.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("SearchIndex manifest is not serializable", e);
        }
    }
}
