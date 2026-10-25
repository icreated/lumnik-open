package io.lumnik.hub.entity;

import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.api.ApplyScopeBoundary;
import io.lumnik.hub.ask.SchemaCardRefresher;
import io.lumnik.hub.audit.AuditRecorder;
import io.lumnik.hub.search.SearchIndexRevalidator;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

@Path("/api/entities/apply")
@RequiresPermission("lm_admin")
public class EntityApplyResource {

  @Inject EntityManifestParser parser;
  @Inject EntityViewCompiler compiler;
  @Inject EntityRegistry registry;
  @Inject SchemaCardRefresher cardRefresher;
  @Inject SearchIndexRevalidator searchIndexes;
  @Inject AuditRecorder audit;

  public record ApplyResult(String name, String view) {}

  @POST
  @Consumes("application/yaml")
  @Produces(MediaType.APPLICATION_JSON)
  public ApplyResult apply(String body) {
    // The message IS the refusal: the twelve docs/entities.md tables promise the exact problem
    // quoted back, and EntityApplyIT asserts it.
    EntityManifest m = ApiErrors.orBadRequest(() -> parser.parse(body));
    // C3, for the kind that never got it. Jackson deserializes this manifest with
    // FAIL_ON_UNKNOWN_PROPERTIES=false, so a key the record does not declare is dropped in silence:
    // `scope:` for `scopes:` empties the scope list and the entity becomes invisible to every bound
    // user, and `colums:` takes the whole grounding block — labels, examples, and the values domain
    // the DomainGuard enforces — with a clean 200 either way. Same words and same order as the
    // View, Process and Workflow doors: one refusal, not one per kind.
    List<String> unknown = parser.unknownKeys(body);
    if (!unknown.isEmpty()) {
      throw new WebApplicationException(Response.status(400)
          .type(MediaType.APPLICATION_JSON)
          .entity(ApiErrors.error("unknown key(s) in the manifest: " + String.join(", ", unknown)
              + " — kind:Entity honors " + EntityManifestParser.honoredSurface()))
          .build());
    }

    // A WRITE door asks allowsAll, not allowsAny. EntityReadResource is right to accept an overlap
    // — one grant is enough to SEE a resource someone else tagged — but publishing into a list you
    // only partly hold widens the scopes you do not. Before the compiler, which writes a view on
    // the BYPASSRLS admin datasource. A scopeless entity is likewise not a bound user's to create:
    // it would be invisible to them by the same rule that makes it visible to everyone else.
    if (!ScopeGrants.allowsAll(m.scopes())) {
      throw new WebApplicationException(ApiErrors.scopeNotGranted());
    }

    // …and the NAME is the other half of the address. EntityViewCompiler.apply conflicts on (name)
    // and sets scopes = EXCLUDED.scopes, so a caller who holds every scope they DECLARE can still
    // rewrite a manifest that lives in scopes they do not — re-homing it, or simply narrowing it out
    // of a métier by re-applying a subset. Same words as the check above (#509).
    //
    // ORDERED — read, then write — which a race can jump; the compiler re-asks atomically and
    // refuseUnlessApplied below is that second answer. This earlier one is kept because it refuses
    // BEFORE the compiler does any work and before the discovery/rediscover side effects, which no
    // rollback reaches (#582).
    ApplyScopeBoundary.refuseUnlessCallerHolds(registry.storedScopes(m.name()));

    try {
      // granted().orElse(null) — empty Optional means unscoped, i.e. every scope. The compiler
      // rolled its whole transaction back on a refusal, DDL included, so the view is as it was.
      ApplyScopeBoundary.refuseUnlessApplied(
          compiler.apply(m, body, ScopeGrants.granted().orElse(null)));
    } catch (MissingSourceTableException e) {
      // Predictable fresh-install state — an honest refusal, not an anonymous 500.
      throw new WebApplicationException(
          Response.status(409)
              .type(MediaType.APPLICATION_JSON)
              .entity(ApiErrors.error(e.getMessage()))
              .build());
    } catch (MissingSourceColumnException | MissingLookupColumnException e) {
      throw new WebApplicationException(ApiErrors.badRequest(e.getMessage()));
    }
    registry.put(m);
    audit.record("Entity", m.name(), "APPLY", Map.of(
        "scopes", m.scopes(), "manifest_sha256", AuditRecorder.sha256(body)));
    // #669: a column this re-apply dropped turns every tenant's SearchIndex that used it invalid.
    // Best-effort by contract — Search is optional and never refuses an Entity apply.
    searchIndexes.afterEntityApplied(m, body);
    // Make the entity askable in its scopes immediately, for the tenant of this request — resolved by
    // TenantContextFilter (header in dev, JWT in prod), NOT a raw header — and refresh every card, in
    // any tenant, that lists its view or describes one of its scopes: a scope it left, a column it
    // dropped, must not stay on a card Ask reads (#1538). Best-effort: the view + manifest are already
    // committed, so a discovery failure must NOT fail the apply. No tenant context → no card is
    // created for a bogus tenant 0; the stored cards are still refreshed.
    cardRefresher.afterEntityChanged(m.name(), CurrentTenantHolder.get(), m.scopes());
    return new ApplyResult(m.name(), EntityViewCompiler.viewName(m.name()));
  }
}
