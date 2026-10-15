package io.lumnik.hub.entity;

import io.lumnik.common.security.RequiresPermission;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.entity.resolution.EntityIdentityRepository;
import io.lumnik.hub.tenant.RequireTenant;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Locale;

/**
 * The read half of #613 lot #622's ambiguity API: list ambiguities for an entity type, and read
 * one source identity's full decision history from the append-only ledger. Same posture as {@link
 * EntityReadResource}: {@code lm_user}, {@code allowsAny} — a bound user sees only what their
 * {@code scope:<tag>} roles reach, refused with the uniform {@code 403 {"error":"scope not
 * granted"}}. Reads go through {@link EntityIdentityRepository}, the same admin-pool, explicit-
 * tenant posture the resolver itself writes through — there is no RLS-enforced view over the
 * crosswalk to read through instead.
 */
@Path("/api/entities/{name}/resolution")
@Produces(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_user")
public class EntityResolutionReadResource {

  @Inject EntityRegistry registry;
  @Inject EntityIdentityRepository repository;

  @GET
  @Path("/ambiguous")
  public Response ambiguous(@PathParam("name") String name,
                             @QueryParam("limit") @DefaultValue("100") int limit,
                             @QueryParam("offset") @DefaultValue("0") int offset) {
    EntityManifest m = ResolutionGate.requireResolvable(registry, name, false);
    String entityType = m.name().toLowerCase(Locale.ROOT);
    long tenantId = RequireTenant.current();
    int boundedLimit = Math.min(Math.max(limit, 1), 1000);
    int boundedOffset = Math.max(offset, 0);
    List<EntityIdentityRepository.AmbiguousIdentity> rows = repository.inTransaction(c ->
        repository.listAmbiguous(c, tenantId, entityType, boundedLimit, boundedOffset));
    return Response.ok(rows).build();
  }

  @GET
  @Path("/identity/{source}/{sourceId}")
  public Response history(@PathParam("name") String name,
                           @PathParam("source") String source,
                           @PathParam("sourceId") String sourceId) {
    EntityManifest m = ResolutionGate.requireResolvable(registry, name, false);
    String entityType = m.name().toLowerCase(Locale.ROOT);
    long tenantId = RequireTenant.current();
    List<EntityIdentityRepository.EvidenceEvent> events = repository.inTransaction(c ->
        repository.evidenceHistory(c, tenantId, entityType, source, sourceId));
    if (events.isEmpty()) {
      return ApiErrors.notFound(
          "no resolution history for " + source + "/" + sourceId + " of entity '" + name + "'");
    }
    return Response.ok(events).build();
  }
}
