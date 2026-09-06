package io.lumnik.hub.api.run;

import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.connector.repository.ConnectorDeadLetterRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.lumnik.hub.tenant.TenantContextFilter;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.UUID;

@Path("/api/platform/runs")
@Produces(MediaType.APPLICATION_JSON)
public class RunAdminResource {

  @Inject ConnectorRunRepository repo;
  @Inject ConnectorDeadLetterRepository dlq;
  @Inject ConnectorRepository connectors;

  @GET
  @Transactional
  public List<RunAdminDto> list(@QueryParam("limit") @DefaultValue("50") int limit) {
    int capped = Math.min(Math.max(limit, 1), 200); // >=1: page(0,0) would throw; limit<=0 is a nonsense request
    // Newest first — the ledger is read top-down for "what just happened"; findAll() alone is unordered.
    var page = repo.findRecent(capped);
    var names = connectors.namesById(page.stream().map(ConnectorRun::getConnectorId).distinct().toList());
    // deadLetterCount is a per-run COUNT (N+1, bounded by the capped page size) — fine at admin scale
    return page.stream()
        .map(r -> RunAdminDto.from(r, dlq.countByRun(r.getId()), names.get(r.getConnectorId())))
        .toList();
  }

  @GET
  @Path("{id}")
  @Transactional
  public Response get(@PathParam("id") UUID id) {
    // Explicit tenant_id guard, as the DLQ ledger does: EntityManager.find() bypasses @Filter.
    var r = repo.find("id = ?1 and tenantId = ?2", id, TenantContextFilter.current()).firstResult();
    if (r == null) return ApiErrors.notFound("run " + id + " not found");
    var names = connectors.namesById(List.of(r.getConnectorId()));
    return Response.ok(RunAdminDto.from(r, dlq.countByRun(r.getId()), names.get(r.getConnectorId()))).build();
  }
}
