package io.lumnik.hub.api.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.mapping.MapColumnsRules;
import io.lumnik.hub.connector.mapping.writer.DynamicTableRegistry;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.lumnik.hub.connector.runtime.ConnectorEngine;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.entity.EntityViewCompiler;
import io.lumnik.hub.tenant.RequireUser;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.POST;
import io.lumnik.hub.connector.runtime.RunWindow;
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
  @Inject EntityRegistry entities;
  @Inject ObjectMapper json;

  @GET
  @Transactional
  public List<ConnectorAdminDto> list() {
    return repo.listAll().stream()
        .map(c -> ConnectorAdminDto.fromWithRun(c,
            runs.findLatestActiveByConnector(c.getId()).orElse(null)))
        .toList();
  }

  @GET
  @Path("{id}")
  @Transactional
  public Response get(@PathParam("id") UUID id) {
    var c = repo.findById(id);
    if (c == null) return ApiErrors.notFound("connector " + id + " not found");
    var lastRun = runs.findLatestActiveByConnector(id).orElse(null);
    return Response.ok(ConnectorAdminDto.fromWithRun(c, lastRun)).build();
  }

  @POST
  @Transactional
  public Response create(ConnectorCreateRequest req) {
    Response bad = refuseBadMapColumns(req.config());
    if (bad == null) bad = refuseBadWindow(req.window());
    if (bad != null) return bad;
    var c = new Connector();
    c.setTenantId(req.tenantId());
    c.setName(req.name());
    c.setTypeId(req.typeId());
    c.setSchedule(req.schedule());
    c.setRunWindow(req.window());
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
    if (c == null) return ApiErrors.notFound("connector " + id + " not found");
    Response bad = refuseBadMapColumns(req.config());
    if (bad == null) bad = refuseBadWindow(req.window());
    if (bad != null) return bad;
    c.setName(req.name());
    c.setTypeId(req.typeId());
    c.setSchedule(req.schedule());
    c.setRunWindow(req.window());
    c.setEnabled(req.enabled());
    c.setConfig(req.config());
    return Response.ok(ConnectorAdminDto.from(c)).build();
  }

  @DELETE
  @Path("{id}")
  @Transactional
  public Response delete(@PathParam("id") UUID id) {
    var c = repo.findById(id);
    if (c == null) return ApiErrors.notFound("connector " + id + " not found");
    Response feeds = refuseIfFeedsEntity(id);
    if (feeds != null) return feeds;
    dynamicTables.purgeForConnector(id);
    repo.delete(c);
    return Response.status(204).build();
  }

  /**
   * Postgres refuses {@code DROP TABLE} while a view depends on the connector's dynamic table — the
   * audit's anonymous 500. Ask the physical dependency graph which view blocks the drop (drift-free —
   * never a stale in-memory guess), and refuse first with a named 409, rather than a silent CASCADE
   * that would take the view out from under a métier surface without warning. The registry is
   * consulted only to word the message: a {@code v_<name>} that is one of ours points at the
   * resolution ({@code lm entity delete}); a foreign view (a DBA's own over the same table) is named
   * as a view to drop, not a dead-end entity gesture.
   */
  private Response refuseIfFeedsEntity(UUID id) {
    Map<String, String> byTable = dynamicTables.dependentViews(id);
    if (byTable.isEmpty()) return null;
    var first = byTable.entrySet().iterator().next();
    String table = first.getKey();          // schema.table
    String view = first.getValue();         // schema.view
    String entity = EntityViewCompiler.entityNameFromView(view);
    String detail = entity != null && entities.findReloadingIfMissing(entity) != null
        ? "table " + table + " feeds entity " + entity
            + " — remove the entity first (lm entity delete " + entity + ")"
        : "table " + table + " has a dependent view " + view + " — drop it first";
    return Response.status(409).type(MediaType.APPLICATION_JSON)
        .entity(ApiErrors.error(detail)).build();
  }

  /**
   * The flat CRUD door persists {@code config} with none of the validation the manifest door
   * ({@code /apply}) runs first. Most of that validation reads the manifest {@code spec} shape,
   * which the stored config is not — but a {@code rename}/{@code map-columns} is written into the
   * stored config identically both ways, and {@link MapColumnsRules} takes a raw node, so the one
   * evidenced run-killer this door lets through — a rename INTO a hub system column, which
   * {@code MapColumnsHook} would only catch at run time after the column is already lost — is
   * refused here at write time, reusing the exact rule the manifest door, the hook and the
   * sibling endpoint door share.
   */
  private Response refuseBadMapColumns(Map<String, Object> config) {
    if (config == null) return null;
    return MapColumnsRules.firstStoredConfigViolation(json.valueToTree(config))
        .map(v -> Response.status(400).type(MediaType.APPLICATION_JSON)
            .entity(ApiErrors.error("config '" + v.path() + "' " + v.reason())).build())
        .orElse(null);
  }

  /**
   * Refuses a malformed {@code window} before it is stored.
   *
   * <p>This door validates almost nothing — a deliberate, documented gap: the manifest door runs
   * {@code ConnectorValidationService}, the flat one does not. That was survivable while the flat
   * shape carried no fence. It carries one now (#499), and {@code RunWindow.contains} is
   * FAIL-OPEN: an unparseable spec answers "yes, run" — so a typo like {@code 22h-6h} would have
   * been accepted here and silently meant NO fence at all, which is the silent-loss class that
   * ticket set out to close, arriving through the field it added.
   *
   * <p>{@code RunWindow.validate} is the same truth {@code WindowField} serves the manifest door,
   * so both refuse the same strings. {@code schedule} has the identical hole and is NOT closed
   * here: it is older than this change and closing it needs the cron validator's own wording —
   * filed, not smuggled in.
   */
  private Response refuseBadWindow(String window) {
    if (window == null || window.isBlank()) return null;   // no fence is legal
    return RunWindow.validate(window).map(ApiErrors::badRequest).orElse(null);
  }

  @POST
  @Path("{id}/run")
  @Consumes(MediaType.WILDCARD)
  public Response runNow(@PathParam("id") UUID id) {
    // Tenant-scoped authorization: the connector must be visible to the REQUEST tenant — RLS itself
    // enforces it (another tenant's connector is a legitimate 404). runNow stays deliberately
    // non-@Transactional (each run row commits in its own tx), so this read opens its own tx.
    boolean visible = QuarkusTransaction.requiringNew()
        .call(() -> repo.findById(id) != null);
    if (!visible) return ApiErrors.notFound("connector " + id + " not found");
    // submitRun persists the connector_run row(s) synchronously (status Running) and returns the
    // REAL run id(s) BEFORE the async chunk loop runs — the caller can immediately dereference them.
    // Deliberately NOT @Transactional: each run row must commit in its own transaction so the async
    // worker (a separate transaction) can read the row it must finalize.
    var runIds = engine.submitRun(id, new RunTrigger.Manual(RequireUser.current()));
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
    if (c == null) return ApiErrors.notFound("connector " + id + " not found");
    c.setEnabled(false);
    return Response.ok(ConnectorAdminDto.from(c)).build();
  }

  @POST
  @Path("{id}/resume")
  @Consumes(MediaType.WILDCARD)
  @Transactional
  public Response resume(@PathParam("id") UUID id) {
    var c = repo.findById(id);
    if (c == null) return ApiErrors.notFound("connector " + id + " not found");
    c.setEnabled(true);
    return Response.ok(ConnectorAdminDto.from(c)).build();
  }
}
