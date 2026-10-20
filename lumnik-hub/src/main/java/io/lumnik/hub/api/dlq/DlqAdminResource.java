package io.lumnik.hub.api.dlq;

import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.api.connector.ConnectorScope;
import io.lumnik.hub.connector.domain.ConnectorDeadLetter;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorDeadLetterRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.lumnik.hub.connector.runtime.DeadLetterCapture;
import io.lumnik.hub.tenant.RequireUser;
import io.lumnik.hub.tenant.TenantContextFilter;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
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
import java.util.UUID;

@Path("/api/platform/dlq")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class DlqAdminResource {

  @Inject ConnectorDeadLetterRepository repo;
  @Inject ConnectorRepository connectors;
  @Inject ConnectorRunRepository runs;
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
    // A dead letter carries the rejected row's values, so each branch asks the connector rule (#1212):
    // a named run or connector of another métier is the uniform 403, the unfiltered list narrows to
    // the connectors the caller holds.
    List<ConnectorDeadLetter> entries;
    if (runId != null) {
      UUID rid = ApiErrors.uuidOrRefuse(runId);
      var run = runs.findByIdForTenant(rid, TenantContextFilter.current());
      if (run != null) ConnectorScope.held(connectors, run.getConnectorId());
      entries = repo.findByRun(rid, capped);
    } else if (connectorId != null) {
      UUID cid = ApiErrors.uuidOrRefuse(connectorId);
      ConnectorScope.held(connectors, cid);
      entries = repo.findPendingByConnector(cid, capped);
    } else {
      // findAllActive (not findAll): exclude soft-deleted/GDPR-erased rows, consistent with the
      // by-run/by-connector branches and GET /{id} — the unfiltered list must not leak erased payloads.
      entries = repo.findAllActive(ConnectorScope.heldIds(connectors), capped);
    }
    var names = connectors.namesById(entries.stream().map(ConnectorDeadLetter::getConnectorId).distinct().toList());
    return entries.stream().map(d -> DlqAdminDto.from(d, names.get(d.getConnectorId()))).toList();
  }

  @GET
  @Path("{id}")
  @Transactional
  public Response get(@PathParam("id") UUID id) {
    var d = held(id);
    if (d == null) return ApiErrors.notFound("DLQ entry " + id + " not found");
    var names = connectors.namesById(List.of(d.getConnectorId()));
    return Response.ok(DlqAdminDto.from(d, names.get(d.getConnectorId()))).build();
  }

  @POST
  @Path("{id}/discard")
  @Transactional
  public Response discard(@PathParam("id") UUID id) {
    // The capture re-reads by id, which bypasses the tenant @Filter: only an entry this tenant
    // holds reaches it (#1216 review).
    if (held(id) == null) return ApiErrors.notFound("DLQ entry " + id + " not found");
    capture.discard(id, RequireUser.current());
    return Response.status(204).build();
  }

  @POST
  @Path("{id}/replayed")
  @Transactional
  public Response markReplayed(@PathParam("id") UUID id,
                                @QueryParam("newRunId") String newRunId) {
    // The capture re-reads by id, which bypasses the tenant @Filter: only an entry this tenant
    // holds reaches it (#1216 review).
    if (held(id) == null) return ApiErrors.notFound("DLQ entry " + id + " not found");
    // Parsed here, not bound as a UUID: a @QueryParam that fails conversion is JAX-RS's 404,
    // where list's run/connector filters refuse the same mistake with a 400 (#1294).
    UUID newRun = newRunId == null ? null : ApiErrors.uuidOrRefuse(newRunId);
    capture.markReplayed(id, newRun, RequireUser.current());
    return Response.status(204).build();
  }

  /**
   * The tenant's live dead letter under {@code id}, null when it has none; 403 thrown when the caller
   * does not hold its connector (#1212). Explicit tenant_id guard: EntityManager.find() bypasses
   * @Filter, so JPQL with an explicit tenantId condition prevents cross-tenant payload leakage.
   * Soft-deleted rows are absent: a GDPR-erased payload must not be fetchable by UUID.
   */
  private ConnectorDeadLetter held(UUID id) {
    var d = repo.find("id = ?1 and tenantId = ?2 and deletedAt is null", id, TenantContextFilter.current())
        .firstResult();
    if (d != null) ConnectorScope.held(connectors, d.getConnectorId());
    return d;
  }
}
