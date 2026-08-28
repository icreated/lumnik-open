package io.lumnik.hub.api.connector;

import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.mapping.writer.DynamicTableRegistry;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.lumnik.hub.connector.runtime.ConnectorEngine;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.DELETE;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/platform/connectors")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ConnectorAdminResource {

  @Inject ConnectorRepository repo;
  @Inject ConnectorEngine engine;
  @Inject ConnectorRunRepository runs;
  @Inject DynamicTableRegistry dynamicTables;

  @GET
  @Transactional
  public List<ConnectorAdminDto> list() {
    return repo.listAll().stream()
        .map(c -> ConnectorAdminDto.fromWithRun(c,
            runs.findLatestByConnector(c.getId()).orElse(null)))
        .toList();
  }

  @GET
  @Path("{id}")
  @Transactional
  public Response get(@PathParam("id") UUID id) {
    var c = repo.findById(id);
    if (c == null) return Response.status(404).build();
    var lastRun = runs.findLatestByConnector(id).orElse(null);
    return Response.ok(ConnectorAdminDto.fromWithRun(c, lastRun)).build();
  }

  @POST
  @Transactional
  public Response create(ConnectorCreateRequest req) {
    var c = new Connector();
    c.setTenantId(req.tenantId());
    c.setName(req.name());
    c.setTypeId(req.typeId());
    c.setSchedule(req.schedule());
    c.setEnabled(req.enabled());
    c.setConfig(req.config());
    repo.persist(c);
    repo.flush();
    return Response.status(201).entity(ConnectorAdminDto.from(c)).build();
  }

  @PUT
  @Path("{id}")
  @Transactional
  public Response update(@PathParam("id") UUID id, ConnectorCreateRequest req) {
    var c = repo.findById(id);
    if (c == null) return Response.status(404).build();
    c.setName(req.name());
    c.setTypeId(req.typeId());
    c.setSchedule(req.schedule());
    c.setEnabled(req.enabled());
    c.setConfig(req.config());
    return Response.ok(ConnectorAdminDto.from(c)).build();
  }

  @DELETE
  @Path("{id}")
  @Transactional
  public Response delete(@PathParam("id") UUID id) {
    var c = repo.findById(id);
    if (c == null) return Response.status(404).build();
    dynamicTables.purgeForConnector(id);
    repo.delete(c);
    return Response.status(204).build();
  }

  @POST
  @Path("{id}/run")
  @Consumes(MediaType.WILDCARD)
  public Response runNow(@PathParam("id") UUID id,
                          @HeaderParam("X-User-Id") String userIdStr) {
    // Tenant-scoped authorization: the connector must be visible to the REQUEST tenant — RLS itself
    // enforces it (another tenant's connector is a legitimate 404). runNow stays deliberately
    // non-@Transactional (each run row commits in its own tx), so this read opens its own tx.
    boolean visible = QuarkusTransaction.requiringNew()
        .call(() -> repo.findById(id) != null);
    if (!visible) return Response.status(404).build();
    Long userId = userIdStr != null ? safeParseLong(userIdStr) : 0L;
    // submitRun persists the connector_run row(s) synchronously (status Running) and returns the
    // REAL run id(s) BEFORE the async chunk loop runs — the caller can immediately dereference them.
    // Deliberately NOT @Transactional: each run row must commit in its own transaction so the async
    // worker (a separate transaction) can read the row it must finalize.
    var runIds = engine.submitRun(id, new RunTrigger.Manual(userId));
    if (runIds.isEmpty()) {
      return Response.status(422)
          .entity(Map.of("error", "no runnable endpoint for connector " + id)).build();
    }
    // Single-endpoint (flat) connectors return one run id under "runId"; multi-endpoint fan-out also
    // exposes every run under "runIds".
    return Response.status(202)
        .entity(Map.of(
            "runId", runIds.get(0).toString(),
            "runIds", runIds.stream().map(UUID::toString).toList()))
        .build();
  }

  @POST
  @Path("{id}/pause")
  @Consumes(MediaType.WILDCARD)
  @Transactional
  public Response pause(@PathParam("id") UUID id) {
    var c = repo.findById(id);
    if (c == null) return Response.status(404).build();
    c.setEnabled(false);
    return Response.ok(ConnectorAdminDto.from(c)).build();
  }

  @POST
  @Path("{id}/resume")
  @Consumes(MediaType.WILDCARD)
  @Transactional
  public Response resume(@PathParam("id") UUID id) {
    var c = repo.findById(id);
    if (c == null) return Response.status(404).build();
    c.setEnabled(true);
    return Response.ok(ConnectorAdminDto.from(c)).build();
  }

  private static Long safeParseLong(String s) {
    try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0L; }
  }
}
