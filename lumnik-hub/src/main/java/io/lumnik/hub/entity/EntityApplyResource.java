package io.lumnik.hub.entity;

import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.hub.ask.DiscoveryService;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

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
      throw new BadRequestException(e.getMessage());
    }
    try {
      compiler.apply(m, body);
    } catch (MissingSourceTableException e) {
      // Predictable fresh-install state — an honest refusal, not an anonymous 500.
      throw new jakarta.ws.rs.WebApplicationException(
          jakarta.ws.rs.core.Response.status(409)
              .type(MediaType.APPLICATION_JSON)
              .entity(io.lumnik.hub.api.ApiErrors.error(e.getMessage()))
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
