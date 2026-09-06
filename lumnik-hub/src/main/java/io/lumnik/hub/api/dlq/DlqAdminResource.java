package io.lumnik.hub.api.dlq;

import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.connector.domain.ConnectorDeadLetter;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorDeadLetterRepository;
import io.lumnik.hub.connector.runtime.DeadLetterCapture;
import io.lumnik.hub.tenant.RequireUser;
import io.lumnik.hub.tenant.TenantContextFilter;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/platform/dlq")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class DlqAdminResource {

  @Inject ConnectorDeadLetterRepository repo;
  @Inject ConnectorRepository connectors;
  @Inject DeadLetterCapture capture;

  @GET
  @Transactional
  public List<DlqAdminDto> list(@QueryParam("limit") @DefaultValue("100") int limit,
                                 @QueryParam("connector") String connectorId,
                                 @QueryParam("run") String runId) {
    // ?run= wins over ?connector= (connector is ignored when both are passed).
    // ?run= = full history for that run (all reasons/resolutions).
    // ?connector= = pending queue only (unresolved entries for that connector).
    int capped = Math.min(Math.max(limit, 1), 200); // >=1: page(0,0) would throw; limit<=0 is a nonsense request
    List<ConnectorDeadLetter> entries;
    if (runId != null) {
      entries = repo.findByRun(parseUuidOr400(runId), capped);
    } else if (connectorId != null) {
      entries = repo.findPendingByConnector(parseUuidOr400(connectorId), capped);
    } else {
      // findAllActive (not findAll): exclude soft-deleted/GDPR-erased rows, consistent with the
      // by-run/by-connector branches and GET /{id} — the unfiltered list must not leak erased payloads.
      entries = repo.findAllActive(capped);
    }
    var names = connectors.namesById(entries.stream().map(ConnectorDeadLetter::getConnectorId).distinct().toList());
    return entries.stream().map(d -> DlqAdminDto.from(d, names.get(d.getConnectorId()))).toList();
  }

  @GET
  @Path("{id}")
  @Transactional
  public Response get(@PathParam("id") UUID id) {
    // Explicit tenant_id guard: EntityManager.find() bypasses @Filter, so we use JPQL with an
    // explicit tenantId condition to prevent cross-tenant payload leakage.
    // Also treat soft-deleted rows as absent: GDPR-erased payload must not be fetchable by UUID.
    Long tenantId = TenantContextFilter.current();
    var d = repo.find("id = ?1 and tenantId = ?2 and deletedAt is null", id, tenantId).firstResult();
    if (d == null) return ApiErrors.notFound("DLQ entry " + id + " not found");
    var names = connectors.namesById(List.of(d.getConnectorId()));
    return Response.ok(DlqAdminDto.from(d, names.get(d.getConnectorId()))).build();
  }

  @POST
  @Path("{id}/discard")
  @Transactional
  public Response discard(@PathParam("id") UUID id) {
    capture.discard(id, RequireUser.current());
    return Response.status(204).build();
  }

  @POST
  @Path("{id}/replayed")
  @Transactional
  public Response markReplayed(@PathParam("id") UUID id,
                                @QueryParam("newRunId") UUID newRunId) {
    capture.markReplayed(id, newRunId, RequireUser.current());
    return Response.status(204).build();
  }

  private static UUID parseUuidOr400(String s) {
    try {
      return UUID.fromString(s);
    } catch (IllegalArgumentException e) {
      // JSON error body for consistency with the other platform 400s (not plain text).
      throw new BadRequestException(
          Response.status(400)
              .entity(Map.of("error", "invalid UUID: " + s))
              .type(MediaType.APPLICATION_JSON)
              .build());
    }
  }
}
