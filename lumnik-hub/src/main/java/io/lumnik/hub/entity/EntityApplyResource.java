package io.lumnik.hub.entity;

import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.api.ApplyScopeBoundary;
import io.lumnik.hub.ask.DiscoveryService;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;

@Path("/api/entities/apply")
@RequiresPermission("lm_admin")
public class EntityApplyResource {

  private static final Logger LOG = Logger.getLogger(EntityApplyResource.class);

  @Inject EntityManifestParser parser;
  @Inject EntityViewCompiler compiler;
  @Inject EntityRegistry registry;
  @Inject DiscoveryService discovery;

  public record ApplyResult(String name, String view) {}

  @POST
  @Consumes("application/yaml")
  @Produces(MediaType.APPLICATION_JSON)
  public ApplyResult apply(String body) {
    EntityManifest m;
    try {
      m = parser.parse(body);
    } catch (IllegalArgumentException e) {
      // Through ApiErrors, NOT `new BadRequestException(message)`: that constructor sets the JAVA
      // exception's message and leaves the RESPONSE bodyless, so every refusal EntityManifestParser
      // raises — the twelve docs/entities.md tables as "quoting the exact problem" — reached lm as
      // "400 (no details from the server)", and nothing was logged either. The throw-shaped twin of
      // the bare Response.status(404) sites #418 fixed; ApiErrors.notFound's javadoc already names
      // this exact failure mode for its own status.
      throw new BadRequestException(ApiErrors.badRequest(e.getMessage()));
    }
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
    ApplyScopeBoundary.refuseUnlessCallerHolds(registry.storedScopes(m.name()));

    try {
      compiler.apply(m, body);
    } catch (MissingSourceTableException e) {
      // Predictable fresh-install state — an honest refusal, not an anonymous 500.
      throw new WebApplicationException(
          Response.status(409)
              .type(MediaType.APPLICATION_JSON)
              .entity(ApiErrors.error(e.getMessage()))
              .build());
    }
    registry.put(m);
    // Make the entity askable in its scopes immediately, for the tenant of this request — resolved by
    // TenantContextFilter (header in dev, JWT in prod), NOT a raw header. Best-effort: the view +
    // manifest are already committed, so a discovery failure must NOT fail the apply (the card can be
    // rebuilt via /rediscover). If there is no tenant context, skip — every tenant picks the entity up
    // on its next rediscover (entity_manifest is global), so we never build a bogus tenant-0 card.
    Long tenant = CurrentTenantHolder.get();
    if (tenant != null) {
      for (String scope : m.scopes()) {
        try {
          discovery.rediscover(tenant, scope);
        } catch (Exception e) {
          LOG.warnf(e, "entity apply: card refresh failed for scope %s", scope);
        }
      }
    }
    return new ApplyResult(m.name(), EntityViewCompiler.viewName(m.name()));
  }
}
