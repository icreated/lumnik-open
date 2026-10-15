package io.lumnik.hub.export;

import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.export.run.ExportRunDispatcher;
import io.lumnik.hub.export.run.ExportRunLedger;
import io.lumnik.hub.export.run.ExportRunLedger.RetrySeed;
import io.lumnik.hub.export.run.ExportRunRequest;
import io.lumnik.hub.export.run.ExportRunStatus;
import io.lumnik.hub.export.run.ExportRunner;
import io.lumnik.hub.export.run.StartResult;
import io.lumnik.hub.tenant.RequireTenant;
import io.lumnik.hub.tenant.RequireUser;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The integrator doors over an export's managed runs: start one, list them, read one, retry a
 * failed one. Every door resolves the manifest first (404) and then its scope (403).
 *
 * <p>The two GETs carry @Transactional for the reason {@link ExportRegistryResource} spells out
 * (SET LOCAL app.current_tenant is transaction-scoped). The two POSTs deliberately do not: the
 * runner claims its row in its own requiringNew transaction and must not run inside a request
 * transaction that would hold the claim uncommitted; their own reads use a short requiringNew
 * transaction instead. Neither POST waits for the run — the worker is a virtual thread and the
 * answer is {@code 202 {"runId"}} as soon as the row is claimed.
 */
@Path("/api/exports/{name}/runs")
@Produces(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_integrator")
public class ExportRunResource {

    @Inject ExportManifestStore store;
    @Inject ExportRunLedger ledger;
    @Inject ExportRunner runner;
    @Inject ExportRunDispatcher dispatcher;

    @POST
    public Response start(@PathParam("name") String name) {
        long tenant = RequireTenant.current();
        Object checked = QuarkusTransaction.requiringNew().call(() -> authorize(tenant, name));
        if (checked instanceof Response refused) return refused;
        ExportManifestStore.Row row = (ExportManifestStore.Row) checked;
        // row.name(), not the path segment: the store resolves names case-insensitively, and the
        // runner requires the request to name the very row it was authorized on.
        return answer(name, started(runner.start(ExportRunRequest.manual(tenant, row.name(), RequireUser.current()),
            row)));
    }

    @GET
    @Transactional
    public Response list(@PathParam("name") String name,
                         @QueryParam("limit") @DefaultValue("50") int limit) {
        long tenant = RequireTenant.current();
        if (authorize(tenant, name) instanceof Response refused) return refused;
        int capped = Math.min(Math.max(limit, 1), 200);
        return Response.ok(ledger.list(tenant, name, capped)).build();
    }

    @GET
    @Path("/{id}")
    @Transactional
    public Response get(@PathParam("name") String name, @PathParam("id") String rawId) {
        long tenant = RequireTenant.current();
        if (authorize(tenant, name) instanceof Response refused) return refused;
        UUID id = ApiErrors.uuidOrRefuse(rawId);
        return ledger.find(tenant, name, id)
            .map(run -> Response.ok(run).build())
            .orElseGet(() -> ApiErrors.notFound("no run " + id + " of export '" + name + "'"));
    }

    @POST
    @Path("/{id}/retry")
    public Response retry(@PathParam("name") String name, @PathParam("id") String rawId) {
        long tenant = RequireTenant.current();
        UUID id = ApiErrors.uuidOrRefuse(rawId);
        Object checked = QuarkusTransaction.requiringNew().call(() -> {
            Object authorized = authorize(tenant, name);
            if (authorized instanceof Response refused) return refused;
            Optional<RetrySeed> seed = ledger.retrySeed(tenant, name, id);
            if (seed.isEmpty()) return ApiErrors.notFound("no run " + id + " of export '" + name + "'");
            if (seed.get().status() != ExportRunStatus.FAILED) {
                return ApiErrors.conflict("run " + id + " is " + seed.get().status().sqlValue()
                    + "; only a Failed run can be retried");
            }
            return new Retry(seed.get(), (ExportManifestStore.Row) authorized);
        });
        if (checked instanceof Response r) return r;
        Retry retry = (Retry) checked;
        RetrySeed seed = retry.seed();
        return answer(name, started(runner.start(new ExportRunRequest(tenant, retry.row().name(), seed.trigger(),
            RequireUser.current(), seed.occurrence(), seed.artifactTime(), seed.nextAttempt(), id),
            retry.row())));
    }

    /** A run the door just queued is not left to the next poll (a no-op unless {@code lumnik.run-queue.enabled}). */
    private StartResult started(StartResult result) {
        if (result instanceof StartResult.Started) dispatcher.wake();
        return result;
    }

    /** A failed run's retry seed and the manifest row the retry was authorized on. */
    private record Retry(RetrySeed seed, ExportManifestStore.Row row) {}

    /**
     * 404 for an unknown export, 403 for one outside the caller's scopes, else the manifest row
     * that was checked. The POSTs hand that very row to the runner, so the definition a run
     * executes is the one whose scope was checked here, not whatever a concurrent re-apply left.
     */
    private Object authorize(long tenant, String name) {
        Optional<ExportManifestStore.Row> row = store.find(tenant, name);
        if (row.isEmpty()) return ApiErrors.notFound("no export named '" + name + "'");
        if (!ScopeGrants.allows(row.get().scope())) return ApiErrors.scopeNotGranted();
        return row.get();
    }

    private static Response answer(String name, StartResult result) {
        return switch (result) {
            case StartResult.Started s -> Response.accepted(Map.of("runId", s.runId())).build();
            case StartResult.AlreadyRunning a -> {
                Map<String, Object> body = new LinkedHashMap<>(
                    ApiErrors.error("export '" + name + "' already has a run in flight"));
                if (a.runId() != null) body.put("runId", a.runId());
                yield Response.status(Response.Status.CONFLICT).entity(body).build();
            }
            case StartResult.OccurrenceTaken o -> ApiErrors.conflict("already delivered");
            case StartResult.NotFound n -> ApiErrors.notFound("no export named '" + name + "'");
        };
    }
}
