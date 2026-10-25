package io.lumnik.hub.api.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.api.connector.handler.CsvFileConnectorApplyHandler;
import io.lumnik.hub.ask.SchemaCardRefresher;
import io.lumnik.hub.audit.AuditRecorder;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorCredential;
import io.lumnik.hub.connector.mapping.MapColumnsRules;
import io.lumnik.hub.connector.mapping.writer.DynamicTableRegistry;
import io.lumnik.hub.connector.repository.ConnectorCredentialRepository;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.lumnik.hub.connector.runtime.ConnectorEngine;
import io.lumnik.hub.connector.runtime.ConnectorRunDispatcher;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.entity.EntityViewCompiler;
import io.lumnik.hub.tenant.RequireTenant;
import io.lumnik.hub.tenant.RequireUser;
import io.lumnik.hub.validator.common.CronField;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Sort;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.POST;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.hub.connector.runtime.RunWindow;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.DELETE;
import jakarta.transaction.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Path("/api/platform/connectors")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ConnectorAdminResource {

  private static final String CSV_FILE = "csv-file";
  private static final Set<String> FLAT_CREATABLE = Set.of(CSV_FILE, "hello-world");

  @Inject ConnectorRepository repo;
  @Inject ConnectorEndpointRepository endpoints;
  @Inject ConnectorCredentialRepository credentials;
  @Inject ConnectorEngine engine;
  @Inject ConnectorRunDispatcher dispatcher;
  @Inject ConnectorRunRepository runs;
  @Inject DynamicTableRegistry dynamicTables;
  @Inject SchemaCardRefresher cardRefresher;
  @Inject EntityRegistry entities;
  @Inject ObjectMapper json;
  @Inject AuditRecorder audit;

  @GET
  @Transactional
  public List<ConnectorAdminDto> list() {
    // A stable order: heap order moves a row on every UPDATE, and a client keeping a cursor on the
    // list (lm ui) saw its rows reshuffle under it (#1107).
    return repo.listAll(Sort.by("name").and("id")).stream()
        .filter(ConnectorScope::holds)   // the by-id doors' rule (#1208)
        .map(c -> ConnectorAdminDto.fromWithRun(c,
            runs.findLatestActiveByConnector(c.getId()).orElse(null)))
        .toList();
  }

  @GET
  @Path("{id}")
  @Transactional
  public Response get(@PathParam("id") UUID id) {
    var c = ConnectorScope.held(repo, id);
    if (c == null) return ApiErrors.notFound("connector " + id + " not found");
    var lastRun = runs.findLatestActiveByConnector(id).orElse(null);
    return Response.ok(ConnectorAdminDto.fromWithRun(c, lastRun)).build();
  }

  @POST
  @Transactional
  public Response create(ConnectorCreateRequest req) {
    ConnectorScope.refuseUnlessHolds(req.tags());   // a bound caller creates inside their scopes only
    long tenant = RequireTenant.current();
    Response bad = refuseOtherTenant(req.tenantId(), tenant);
    if (bad == null) bad = refuseUncreatableType(req.typeId());
    if (bad == null) bad = refuseBadMapColumns(req.config());
    if (bad == null) bad = refuseBadSchedule(req.schedule());
    if (bad == null) bad = refuseBadWindow(req.window());
    if (bad == null) bad = refuseBadTags(req.tags());
    if (bad == null) bad = refuseTakenName(tenant, req.name(),
        " — to update it, add that id to the file you apply");
    if (bad != null) return bad;
    var c = new Connector();
    c.setTenantId(tenant);
    c.setName(req.name());
    c.setTypeId(req.typeId());
    c.setSchedule(req.schedule());
    c.setRunWindow(req.window());
    c.setEnabled(req.enabled());
    c.setConfig(req.config());
    c.setTags(req.tags() != null ? req.tags() : List.of());
    repo.persist(c);
    repo.flush();
    // The manifest door's endpoint, by the manifest door's rule (#1189). hello-world declares none:
    // the engine gives it one at its first run, carrying the config — its count — as it is.
    if (CSV_FILE.equals(c.getTypeId())) CsvFileConnectorApplyHandler.defaultEndpoint(endpoints, c);
    recordWrite(c, "APPLY");
    return Response.status(201).entity(ConnectorAdminDto.from(c)).build();
  }

  /**
   * The tenant is the caller's (#1190): an absent {@code tenantId} means it, and one naming another
   * tenant is the caller's mistake — said here, rather than left to RLS to refuse as a 500.
   */
  private static Response refuseOtherTenant(Long requested, long tenant) {
    if (requested == null || requested == tenant) return null;
    return ApiErrors.badRequest("tenantId (" + requested + ") does not match the authenticated tenant ("
        + tenant + ") — omit it, the connector is written in yours");
  }

  /**
   * This door creates only the types whose endpoint the flat fields can describe (#1189). A
   * jdbc-generic connector's tables, a rest-generic one's endpoints and a kafka one's topic live in
   * its manifest, never in the stored config this door reads: created here, it could never run.
   * Updating one stays open — the endpoints are already there.
   */
  private static Response refuseUncreatableType(String typeId) {
    if (typeId == null) return ApiErrors.badRequest("typeId is required");
    if (FLAT_CREATABLE.contains(typeId)) return null;
    return ApiErrors.badRequest("a " + typeId + " connector is created from its kind: Connector manifest"
        + " with lm apply -f — this door creates csv-file and hello-world connectors, and updates any");
  }

  /**
   * A name is unique in the tenant (#1224): another connector holding it is a 409 naming it, where
   * the insert or the rename used to reach the {@code (tenant_id, name)} constraint as a 500. A
   * holder the caller does not hold is the uniform 403 instead — the one bit the manifest door
   * already gives ({@code ApplyScopeBoundary}), never its id. The holder is read through the one
   * (tenant, name) address, locked even when free (#1231): a racing writer waits, then is answered here.
   */
  private Response refuseTakenName(long tenant, String name, String gesture) {
    if (name == null) return null;
    Connector holder = repo.byAddressForUpdate(tenant, name).orElse(null);
    if (holder == null) return null;
    ConnectorScope.refuseUnlessHolds(holder.getTags());
    UUID taken = holder.getId();
    return ApiErrors.conflict("connector \"" + name + "\" already exists (id " + taken + ")" + gesture,
        "id", taken.toString());
  }

  /**
   * A connector's type is not an edit: its endpoints, cursor and tables belong to the type, so a
   * hello-world turned csv-file has no endpoint to run, and one turned jdbc-generic walks around the
   * create refusal above (#1189). An absent {@code typeId} keeps the stored one — silence is not a
   * value here either, as for {@code tags}.
   */
  private static Response refuseTypeChange(String requested, String stored) {
    if (requested == null || requested.equals(stored)) return null;
    return ApiErrors.badRequest("typeId cannot change (" + stored + " -> " + requested
        + ") — delete the connector and create it again as " + requested);
  }

  @PUT
  @Path("{id}")
  @Transactional
  public Response update(@PathParam("id") UUID id, ConnectorCreateRequest req) {
    var c = ConnectorScope.heldForUpdate(repo, id);
    if (c == null) return ApiErrors.notFound("connector " + id + " not found");
    if (req.tags() != null) ConnectorScope.refuseUnlessHolds(req.tags());
    Response bad = refuseOtherTenant(req.tenantId(), c.getTenantId());
    if (bad == null) bad = refuseTypeChange(req.typeId(), c.getTypeId());
    if (bad == null) bad = refuseBadMapColumns(req.config());
    if (bad == null) bad = refuseBadSchedule(req.schedule());
    if (bad == null) bad = refuseBadWindow(req.window());
    if (bad == null) bad = refuseBadTags(req.tags());
    if (bad == null && !Objects.equals(req.name(), c.getName()))
      bad = refuseTakenName(c.getTenantId(), req.name(), " — choose another name");
    if (bad != null) return bad;
    c.setName(req.name());
    c.setSchedule(req.schedule());
    c.setRunWindow(req.window());
    c.setEnabled(req.enabled());
    c.setConfig(req.config());
    // Unlike window/schedule/config, tags double as the scope:<tag> read boundary
    // (ConnectorTagsResource's javadoc) — an empty list means UNSCOPED, tenant-wide visible, not a
    // harmless default. A full-replace null->List.of() here (this door's usual "absent means
    // reset" rule) would let any caller that omits tags from a PUT body silently broaden a scoped
    // connector's data to the whole tenant. null therefore means "not specified, leave the
    // existing grant alone"; only an explicit (even empty) array replaces it — the one field on
    // this door where the request's silence must NOT read as a value.
    if (req.tags() != null) {
      cardRefresher.afterRetag(c.getTenantId(), c.getTags(), req.tags());
      c.setTags(req.tags());
    }
    recordWrite(c, "APPLY");
    return Response.ok(ConnectorAdminDto.from(c)).build();
  }

  /**
   * {@code dryRun=true} runs the same refusals and answers the tables the delete would drop or empty,
   * with the rows that would go, deleting nothing (#1218) — so the CLI refuses, or says what goes,
   * before it asks. The connector's credential goes with the last connector that authenticates with
   * it, and the dry run names it then (#1250).
   */
  @DELETE
  @Path("{id}")
  @Transactional
  public Response delete(@PathParam("id") UUID id, @QueryParam("dryRun") boolean dryRun) {
    // A dry run writes nothing: it holds no lock the connector's other writers would wait on while it counts.
    var c = dryRun ? ConnectorScope.held(repo, id) : ConnectorScope.heldForUpdate(repo, id);
    if (c == null) return ApiErrors.notFound("connector " + id + " not found");
    Response feeds = refuseIfFeedsEntity(id);
    if (feeds != null) return feeds;
    if (dryRun) {
      List<Map<String, Object>> tables = dynamicTables.rowsGoingWith(id).entrySet().stream()
          .map(t -> Map.<String, Object>of("table", t.getKey(), "rows", t.getValue()))
          .toList();
      Map<String, Object> takes = new LinkedHashMap<>(Map.of("dryRun", true, "tables", tables));
      ConnectorCredential credential = credentialGoingWith(c, LockModeType.NONE);
      if (credential != null) takes.put("credential", credential.getName());
      return Response.ok(takes).build();
    }
    List<String> scopes = dynamicTables.scopesOfTablesWrittenBy(c.getTenantId(), id);
    dynamicTables.purgeForConnector(id);
    ConnectorCredential credential = credentialGoingWith(c, LockModeType.PESSIMISTIC_WRITE);
    repo.delete(c);
    if (credential != null) credentials.delete(credential);
    recordWrite(c, "DELETE");
    cardRefresher.afterCommit(c.getTenantId(), scopes);
    return Response.status(204).build();
  }

  /**
   * The connector's credential when no other connector of the tenant authenticates with it, else
   * null. Locked first when it is about to go: two deletes of a credential's last two connectors
   * then count one after the other, and the second sees it is the last.
   */
  private ConnectorCredential credentialGoingWith(Connector c, LockModeType lock) {
    if (c.getCredentialId() == null) return null;
    // The tenant is the query's own predicate, never findById: an id lookup bypasses the tenant @Filter.
    ConnectorCredential credential = credentials
        .find("id = ?1 and tenantId = ?2", c.getCredentialId(), c.getTenantId())
        .withLock(lock).firstResult();
    if (credential == null || repo.count("credentialId = ?1 and id <> ?2 and tenantId = ?3",
        c.getCredentialId(), c.getId(), c.getTenantId()) > 0) return null;
    return credential;
  }

  /** One audit row per connector write through this flat door (#1193) — what was written, never its config. */
  private void recordWrite(Connector c, String operation) {
    List<String> tags = c.getTags() != null ? List.copyOf(c.getTags()) : List.of();
    audit.recordAfterCommit("Connector", c.getId().toString(), operation, Map.of(
        "via", "api", "name", c.getName(), "type", c.getTypeId(), "tags", tags));
  }

  /**
   * Postgres refuses {@code DROP TABLE} while a view depends on the connector's dynamic table — the
   * audit's anonymous 500. Ask the physical dependency graph which view blocks the drop (drift-free —
   * never a stale in-memory guess), and refuse first with a named 409, rather than a silent CASCADE
   * that would take the view out from under a business app without warning. The registry is
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
   * Refuses a malformed {@code schedule} before it is stored (#523).
   *
   * <p>Unlike {@code window}, {@code ConnectorScheduler.isCronDue} fails <b>safe</b>, not open: an
   * unparseable cron is caught, logged at WARN, and the connector is treated as never-due — it
   * cannot silently run unfenced. But "safe" there still means silent from the operator's side: a
   * typo'd {@code schedule} just never fires again, forever, discoverable only by reading server
   * logs. Refusing it here, at the door {@code lm connector apply} and the TUI's edit loop drive,
   * turns that into an immediate 400 instead of a connector that quietly stops mattering.
   *
   * <p>{@code CronField.validCron(String)} is the same UNIX-cron parser the manifest door's
   * {@code CronField.check} runs per connector type, so both refuse the same strings.
   */
  private Response refuseBadSchedule(String schedule) {
    if (schedule == null || schedule.isBlank()) return null;   // no schedule is legal
    return CronField.validCron(schedule).map(ApiErrors::badRequest).orElse(null);
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
   * so both refuse the same strings. {@code schedule} had the identical hole; {@link
   * #refuseBadSchedule} closes it.
   */
  private Response refuseBadWindow(String window) {
    if (window == null || window.isBlank()) return null;   // no fence is legal
    return RunWindow.validate(window).map(ApiErrors::badRequest).orElse(null);
  }

  /**
   * Refuses a malformed tag before it is stored, reusing the exact rule the dedicated
   * {@code /tags} door ({@link ConnectorTagsResource#set}) already enforces — so a tag {@code
   * apply} accepts here cannot be refused by {@code lm connector scope set} one command later.
   */
  private Response refuseBadTags(List<String> tags) {
    if (tags == null) return null;   // no tags is legal
    for (String t : tags) {
      var problem = ScopeGrants.nameProblem(t);
      if (problem.isPresent()) return ApiErrors.badRequest(problem.get());
    }
    return null;
  }

  @POST
  @Path("{id}/run")
  @Consumes(MediaType.WILDCARD)
  public Response runNow(@PathParam("id") UUID id) {
    // Tenant-scoped authorization: the connector must be visible to the REQUEST tenant — RLS itself
    // enforces it (another tenant's connector is a legitimate 404). runNow stays deliberately
    // non-@Transactional (each run row commits in its own tx), so this read opens its own tx.
    // Unlocked, unlike the doors that write (#1217): the run rows commit in transactions of their
    // own, which a lock taken here would have to span, and what the run lands is read under the
    // tags the connector carries when it is read — a retag committing just after this decision
    // starts at most one run the caller can no longer read, and moves nothing out of a scope.
    boolean visible = QuarkusTransaction.requiringNew()
        .call(() -> ConnectorScope.held(repo, id) != null);
    if (!visible) return ApiErrors.notFound("connector " + id + " not found");
    // submitRun persists the connector_run row(s) synchronously (status Running, or Queued with the run queue on) and returns the
    // REAL run id(s) BEFORE the async chunk loop runs — the caller can immediately dereference them.
    // Deliberately NOT @Transactional: each run row must commit in its own transaction so the async
    // worker (a separate transaction) can read the row it must finalize.
    var runIds = engine.submitRun(id, new RunTrigger.Manual(RequireUser.current()));
    if (runIds.isEmpty()) {
      return Response.status(422)
          .entity(Map.of("error", "no runnable endpoint for connector " + id)).build();
    }
    dispatcher.wake();   // a no-op unless the door queued (lumnik.run-queue.enabled)
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
    var c = ConnectorScope.heldForUpdate(repo, id);
    if (c == null) return ApiErrors.notFound("connector " + id + " not found");
    c.setEnabled(false);
    return Response.ok(ConnectorAdminDto.from(c)).build();
  }

  @POST
  @Path("{id}/resume")
  @Consumes(MediaType.WILDCARD)
  @Transactional
  public Response resume(@PathParam("id") UUID id) {
    var c = ConnectorScope.heldForUpdate(repo, id);
    if (c == null) return ApiErrors.notFound("connector " + id + " not found");
    c.setEnabled(true);
    return Response.ok(ConnectorAdminDto.from(c)).build();
  }
}
