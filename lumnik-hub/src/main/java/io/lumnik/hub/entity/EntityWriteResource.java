package io.lumnik.hub.entity;

import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.ask.DiscoveryService;
import io.lumnik.hub.search.SearchIndexStore;
import io.lumnik.hub.tenant.TenantContextFilter;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * The integrator's write door for canonical entities — global and {@code lm_admin}, the inverse of
 * {@code /api/entities/apply}. Kept off {@link EntityReadResource} on purpose: that class stays a
 * read of the RLS datasource, and this one never drags a write-side collaborator into an RLS read
 * path (the same reason {@code EntityApplyResource} is its own door). Its collaborators are apply's
 * — the compiler, the registry, discovery — not the read resource's.
 */
@Path("/api/entities")
@Produces(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_admin")
public class EntityWriteResource {

  private static final Logger LOG = Logger.getLogger(EntityWriteResource.class);

  @Inject EntityRegistry registry;
  @Inject EntityViewCompiler compiler;
  @Inject DiscoveryService discovery;
  @Inject SearchIndexStore searchIndexStore;

  /**
   * Remove an entity: purge every tenant's SearchIndex over it, drop its view, delete its manifest
   * row (atomically, admin DS), evict the in-memory entry, and rediscover its scopes best-effort so
   * the RAG schema card drops it. Same scope asymmetry as apply — a bound integrator must hold ALL
   * the entity's scopes (allowsAll, the write rule); an unknown name is a 404. Not
   * {@code @Transactional}: the compiler runs its own atomic admin-DS transaction and rediscover
   * needs none, mirroring apply.
   *
   * <p>Trigger 6 (design §4, row 6): {@code purgeAllForEntity} runs INSIDE {@code compiler.delete}'s
   * own transaction (its {@link EntityViewCompiler.ConnectionWork} overload), not before it as a
   * separately-committed admin-DS statement — a review of this PR (#709) found that ordering left a
   * window where the purge had already landed but the DROP VIEW/manifest delete had not, if the
   * latter then failed. One commit now covers both, so a deleted entity's already-projected
   * documents are never stranded AND the SearchIndex rows can never disappear without the entity
   * itself actually going with them.
   */
  @DELETE
  @Path("/{name}")
  public Response delete(@PathParam("name") String name) {
    // entity_manifest is global; a second hub instance's map may be cold. findReloadingIfMissing
    // re-reads the DB once before we decide the entity does not exist, so a valid delete is never a
    // false 404.
    EntityManifest m = registry.findReloadingIfMissing(name);
    if (m == null) return ApiErrors.notFound("entity " + name + " not found");
    if (!ScopeGrants.allowsAll(m.scopes())) return ApiErrors.scopeNotGranted();
    Long actor = TenantContextFilter.currentUser();
    compiler.delete(m.name(), c -> searchIndexStore.purgeAllForEntity(c, m.name(), actor));
    registry.remove(m.name());
    // Best-effort, like apply: the view + manifest row are already gone, so a card-refresh failure
    // must not fail the delete. No tenant context (system caller) → skip: every tenant picks the
    // removal up on its next rediscover, since entity_manifest is global.
    Long tenant = CurrentTenantHolder.get();
    if (tenant != null) {
      for (String scope : m.scopes()) {
        try {
          discovery.rediscover(tenant, scope);
        } catch (Exception e) {
          LOG.warnf(e, "entity delete: card refresh failed for scope %s", scope);
        }
      }
    }
    return Response.status(204).build();
  }
}
