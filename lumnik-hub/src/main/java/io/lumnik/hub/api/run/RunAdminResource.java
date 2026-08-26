package io.lumnik.hub.api.run;

import io.lumnik.hub.connector.repository.ConnectorDeadLetterRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
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
  @Inject io.lumnik.hub.connector.repository.ConnectorRepository connectors;

  @GET
  @Transactional
  public List<RunAdminDto> list(@QueryParam("limit") @DefaultValue("50") int limit) {
    int capped = Math.min(Math.max(limit, 1), 200); // >=1: page(0,0) would throw; limit<=0 is a nonsense request
    var page = repo.findAll().page(0, capped).list();
    // One batch query resolves every connector name for the page — the ledger is for
    // human diagnosis, and it used to speak only in UUIDs.
    var ids = page.stream().map(io.lumnik.hub.connector.domain.ConnectorRun::getConnectorId)
        .filter(java.util.Objects::nonNull).distinct().toList();
    var names = ids.isEmpty() ? java.util.Map.<UUID, String>of()
        : connectors.list("id in ?1", ids).stream()
            .collect(java.util.stream.Collectors.toMap(
                io.lumnik.hub.connector.domain.Connector::getId,
                io.lumnik.hub.connector.domain.Connector::getName));
    // deadLetterCount is a per-run COUNT (N+1, bounded by the capped page size) — fine at admin scale
    return page.stream()
        .map(r -> RunAdminDto.from(r, dlq.countByRun(r.getId()), names.get(r.getConnectorId())))
        .toList();
  }

  @GET
  @Path("{id}")
  @Transactional
  public Response get(@PathParam("id") UUID id) {
    var r = repo.findById(id);
    if (r == null) return Response.status(404).build();
    var c = r.getConnectorId() == null ? null : connectors.findById(r.getConnectorId());
    return Response.ok(RunAdminDto.from(r, dlq.countByRun(r.getId()),
        c == null ? null : c.getName())).build();
  }
}
