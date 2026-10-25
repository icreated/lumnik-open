package io.lumnik.hub.export;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.api.ApplyDoorSupport;
import io.lumnik.hub.api.ApplyScopeBoundary;
import io.lumnik.hub.audit.AuditRecorder;
import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.export.format.ColumnFormatterRegistry;
import io.lumnik.hub.tenant.RequireTenant;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Curated writes to the export manifest store: apply = validate-then-upsert. Every field the
 * parser cannot check on its own — is the referenced entity real, is spec.scope one of its
 * declared scopes, is every spec.columns[i] one of its canonical columns — is asked here, after
 * the parser hands back a structurally valid manifest.
 */
@Path("/api/exports/apply")
@Produces(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_integrator")
public class ExportApplyResource {

    static final Set<String> RESERVED_NAMES = Set.of("apply");

    @Inject ExportManifestStore store;
    @Inject EntityRegistry entities;
    @Inject AuditRecorder audit;
    @Inject ColumnFormatterRegistry formatters;

    private final ExportManifestParser parser = new ExportManifestParser();

    @POST
    @Consumes({"application/yaml", "text/yaml", MediaType.APPLICATION_JSON})
    @Transactional
    public Response apply(String body) {
        long tenant = RequireTenant.current();

        JsonNode root = ApiErrors.orBadRequest(() -> parser.readTree(body));

        // Must run BEFORE unknownKeys(): these four names are never in the allowed-keys surface,
        // so the generic refusal below would otherwise reach spec.destination.password first and
        // this specific redirect would never fire.
        Optional<Response> credRefusal = parser.refuseInlineCredentials(root);
        if (credRefusal.isPresent()) return credRefusal.get();

        List<String> unknown = parser.unknownKeys(root);
        Optional<Response> unknownRefusal =
            ApplyDoorSupport.refuseUnknownKeys("Export", unknown, ExportManifestParser.honoredSurface());
        if (unknownRefusal.isPresent()) return unknownRefusal.get();

        ExportManifest manifest = ApiErrors.orBadRequest(() -> parser.parse(root));

        String name = manifest.name().strip().toLowerCase(Locale.ROOT);
        Optional<Response> slugRefusal = ApplyDoorSupport.refuseReservedOrInvalidSlug(name, RESERVED_NAMES);
        if (slugRefusal.isPresent()) return slugRefusal.get();

        if (!ScopeGrants.allows(manifest.scope())) {
            return ApiErrors.scopeNotGranted();
        }

        // ORDERED — read, then write, which a race can jump; the store re-asks atomically below.
        // It runs HERE, before the entity is even resolved, for ViewApplyResource's own reason:
        // the cross-checks below name the entity's declared scopes and its canonical columns, and
        // the entity registry is deployment-global — so a caller refused for the NAME must not
        // read another scope's entity shape on the way out (#582). It also refuses before the
        // audit record below and any future side effect this one cannot roll back (#509).
        ApplyScopeBoundary.refuseUnlessCallerHoldsScope(store.findScope(tenant, name));

        EntityManifest entity = entities.findReloadingIfMissing(manifest.entity());
        if (ExportDefinitionCheck.resolve(manifest, entity, formatters)
                instanceof ExportDefinitionCheck.Problem problem) {
            return ApiErrors.badRequest(problem.sentence());
        }

        // entity.name(), not manifest.entity(): EntityRegistry resolves case-insensitively, so
        // storing the raw spelling would let `AcmeCustomer` and `acmecustomer` sit in two rows
        // naming one entity — a later ticket querying entity_name would see two.
        ApplyScopeBoundary.refuseUnlessApplied(store.upsert(tenant, name, manifest.scope(),
            entity.name(), manifest.destination().kind(), manifest.schedule(), body,
            ScopeGrants.granted().orElse(null)));

        // entity.name(), not manifest.entity(), here too — same reason as the store call above:
        // the caller's raw spelling and the canonical one can differ, and reporting the raw one
        // here would make `apply`'s own response disagree with what `list`/`get` show for the
        // same row.
        audit.record("Export", name, "APPLY", Map.of(
            "scope", manifest.scope(),
            "entity", entity.name(),
            "manifest_sha256", AuditRecorder.sha256(body)));

        return Response.ok(Map.of(
            "name", name, "scope", manifest.scope(), "entity", entity.name())).build();
    }
}
