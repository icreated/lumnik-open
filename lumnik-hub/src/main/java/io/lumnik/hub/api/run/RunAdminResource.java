package io.lumnik.hub.api.run;

import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.connector.domain.ConnectorRunStatus;
import io.lumnik.hub.connector.repository.ConnectorDeadLetterRepository;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.lumnik.hub.connector.runtime.ConnectorEngine;
import io.lumnik.hub.connector.runtime.ConnectorRunDispatcher;
import io.lumnik.hub.tenant.TenantContextFilter;
import jakarta.inject.Inject;
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
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Path("/api/platform/runs")
@Produces(MediaType.APPLICATION_JSON)
public class RunAdminResource {

  @Inject ConnectorRunRepository repo;
  @Inject ConnectorDeadLetterRepository dlq;
  @Inject ConnectorRepository connectors;
  @Inject ConnectorEndpointRepository endpoints;
  @Inject ConnectorEngine engine;
  @Inject ConnectorRunDispatcher dispatcher;

  @GET
  @Transactional
  public List<RunAdminDto> list(@QueryParam("limit") @DefaultValue("50") int limit,
                                @QueryParam("connector") String connectorId,
                                @QueryParam("endpoint") String endpointId) {
    int capped = Math.min(Math.max(limit, 1), 200); // >=1: page(0,0) would throw; limit<=0 is a nonsense request
    // Newest first — the ledger is read top-down for "what just happened"; findAll() alone is unordered.
    // Both filters narrow the QUERY, not its result: the ledger is global, so a page filtered after
    // the fact answers about the hub's newest N runs rather than what was asked for — on a busy hub
    // a quiet connector came back as an empty table, read as "this connector never ran".
    // ?endpoint= is the finer half of the same address: a connector fans out and writes one row per
    // endpoint, so ?connector= cannot answer "this table's runs" — which is what a drill-down asks.
    var page = repo.findRecent(TenantContextFilter.current(), filterId(connectorId), filterId(endpointId), capped);
    var names = connectors.namesById(page.stream().map(ConnectorRun::getConnectorId).distinct().toList());
    // One more projection, one more query: without it a connector that fans out over several
    // endpoints writes rows this ledger cannot tell apart. A run with no endpoint contributes no
    // id and gets no name — and the null is checked BEFORE the lookup below, not after: a page
    // where no run has an endpoint leaves namesById returning Map.of(), whose get(null) throws
    // rather than answering null.
    var endpointNames = endpoints.namesById(
        page.stream().map(ConnectorRun::getEndpointId).filter(Objects::nonNull).distinct().toList());
    // One grouped COUNT for the whole page: it used to be one per run, up to 200 on each refresh (#892).
    var deadLetters = dlq.countByRuns(page.stream().map(ConnectorRun::getId).toList());
    return page.stream()
        .map(r -> RunAdminDto.from(r, deadLetters.get(r.getId()), names.get(r.getConnectorId()),
            r.getEndpointId() == null ? null : endpointNames.get(r.getEndpointId())))
        .toList();
  }

  @GET
  @Path("{id}")
  @Transactional
  public Response get(@PathParam("id") UUID id) {
    var r = repo.findByIdForTenant(id, TenantContextFilter.current());
    if (r == null) return ApiErrors.notFound("run " + id + " not found");
    var names = connectors.namesById(List.of(r.getConnectorId()));
    // null when the run has no endpoint: a row predating endpoints, or one whose endpoint was
    // hard-deleted (ON DELETE SET NULL, V13). List.of(null) would throw either way.
    var endpointName = r.getEndpointId() == null ? null
        : endpoints.namesById(List.of(r.getEndpointId())).get(r.getEndpointId());
    return Response.ok(RunAdminDto.from(r, dlq.countByRun(r.getId()),
        names.get(r.getConnectorId()), endpointName)).build();
  }

  /** A filter id, or null when the caller gave none — never ignored when it cannot be read. */
  private static UUID filterId(String raw) {
    return raw == null ? null : ApiErrors.uuidOrRefuse(raw);
  }

  /**
   * Requests cancellation of a run. Cooperative, same as {@link ConnectorEngine#cancel(UUID)}: a
   * 202 means the request was recorded, not that the run has stopped — it ends after the chunk in
   * flight, or may complete normally if that chunk was already reaching EndOfStream.
   *
   * <p>There is a real window between the run row being created ({@code Running} in the ledger) and
   * the engine's chunk loop registering the run (endpoint/connector lookup, advisory-lock
   * acquisition — several DB round-trips): a cancel arriving in that window finds a {@code Running}
   * row but no chunk loop watching it yet, and is refused with the same 409 as a genuinely orphaned
   * run. That is honest, not a bug — in that window, a retry a moment later succeeds once the loop
   * has registered; a genuinely orphaned run — one with no chunk loop in this JVM watching it —
   * sees no retry succeed.
   *
   * <p>No role annotation here: {@code /api/platform/*} is already integrator-only via
   * {@code PlatformApiAuthFilter}.
   */
  @POST
  @Path("{id}/cancel")
  @Consumes(MediaType.WILDCARD)
  @Transactional
  public Response cancel(@PathParam("id") UUID id) {
    // Same tenant-scoped lookup as get(): engine.cancel(UUID) is blind to the tenant, so this
    // read is the tenant boundary.
    var r = repo.findByIdForTenant(id, TenantContextFilter.current());
    if (r == null) return ApiErrors.notFound("run " + id + " not found");

    // A queued run belongs to no worker: it is ended here, not cancelled cooperatively (#952).
    if (ConnectorRunStatus.QUEUED.sqlValue().equals(r.getStatus())) {
      var outcome = dispatcher.cancelQueued(id, TenantContextFilter.current());
      if (outcome == ConnectorRunDispatcher.QueuedCancel.CANCELLED) {
        return Response.status(202).type(MediaType.APPLICATION_JSON)
            .entity(Map.of("status", "cancelled", "runId", id.toString())).build();
      }
      if (outcome == ConnectorRunDispatcher.QueuedCancel.BUSY) {
        return Response.status(409).type(MediaType.APPLICATION_JSON)
            .entity(ApiErrors.error("run " + id + " is being started; try again in a moment")).build();
      }
      // It started or ended while the cancel waited: read what it is now and go on as for any other run.
      repo.getEntityManager().clear();
      r = repo.findByIdForTenant(id, TenantContextFilter.current());
      if (r == null) return ApiErrors.notFound("run " + id + " not found");
    }

    if (!ConnectorRunStatus.isRunning(r.getStatus())) {
      return Response.status(409).type(MediaType.APPLICATION_JSON)
          .entity(ApiErrors.error("run " + id + " already ended (" + r.getStatus() + ")")).build();
    }

    // engine.cancel() mutates in-JVM state (liveRuns) that a transaction rollback cannot undo —
    // safe here because nothing above this point has dirtied the entity, so there is nothing to
    // roll back if the request fails after this line.
    if (!engine.cancel(id)) {
      // A lost race, not necessarily an orphan: the run may have ended naturally between the
      // Running read above and this call, in which case liveRuns no longer holds it for a reason
      // unrelated to "nothing is watching this run". Re-read past the L1 cache — this row is
      // already managed in this @Transactional's persistence context, so a naive re-read would
      // hand back the stale Running snapshot instead of the committed terminal status (the same
      // trap ConnectorEngine.currentCursor() clears the persistence context for) — so the 409
      // names the real cause instead of mislabeling it.
      repo.getEntityManager().clear();
      var after = repo.findByIdForTenant(id, TenantContextFilter.current());
      if (after != null && !ConnectorRunStatus.isRunning(after.getStatus())) {
        return Response.status(409).type(MediaType.APPLICATION_JSON)
            .entity(ApiErrors.error("run " + id + " already ended (" + after.getStatus() + ")")).build();
      }
      return Response.status(409).type(MediaType.APPLICATION_JSON)
          .entity(ApiErrors.error("run " + id + " is not in flight on this hub")).build();
    }

    return Response.status(202).type(MediaType.APPLICATION_JSON)
        .entity(Map.of("status", "cancellation requested", "runId", id.toString())).build();
  }
}
