package io.lumnik.hub.api.search;

import io.lumnik.common.security.ScopeGrants;
import io.lumnik.extraction.spi.search.ProjectionSink;
import io.lumnik.extraction.spi.search.SearchAvailability;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.audit.AuditRecorder;
import io.lumnik.hub.search.ProjectionSinkGate;
import io.lumnik.hub.search.SearchGate;
import io.lumnik.hub.search.SearchIndexManifest;
import io.lumnik.hub.search.SearchIndexManifestParser;
import io.lumnik.hub.search.SearchIndexStore;
import io.lumnik.hub.search.mark.SearchProjectionMarkStore;
import io.lumnik.hub.search.projection.CancelResult;
import io.lumnik.hub.search.projection.RebuildClaim;
import io.lumnik.hub.search.projection.SearchProjectionState;
import io.lumnik.hub.search.projection.SearchProjectionStateStore;
import io.lumnik.hub.search.projection.SearchProjectionWorker;
import io.lumnik.hub.tenant.TenantContextFilter;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/**
 * #703's operations door: {@code /api/platform/search/*} — list/status/rebuild/cancel over #669's
 * {@code kind: SearchIndex} declarations, composing the read model {@link SearchIndexStore},
 * {@link SearchProjectionStateStore} and {@link SearchProjectionMarkStore} together with the write
 * seams {@link SearchProjectionWorker#triggerRebuild} and {@link
 * SearchProjectionStateStore#requestCancel}. Integrator-only via {@code PlatformApiAuthFilter}, same
 * as every other {@code /api/platform/*} door ({@link io.lumnik.hub.api.run.RunAdminResource}) — no
 * role annotation needed here.
 *
 * <p>The scope guard reads the SAME entity scopes {@code SearchIndexStore}'s own list/apply doors
 * already ask: reading (list/status) asks {@link ScopeGrants#allowsAny}, exactly like {@code
 * SearchIndexListResource}'s own read door — an operations action is still a read of the
 * declaration, never a write to it.
 */
@Path("/api/platform/search")
@Produces(MediaType.APPLICATION_JSON)
public class SearchOperationsResource {

    @Inject SearchIndexStore indexStore;
    @Inject SearchProjectionStateStore stateStore;
    @Inject SearchProjectionMarkStore markStore;
    @Inject ProjectionSinkGate sinkGate;
    @Inject SearchGate searchGate;
    @Inject SearchProjectionWorker worker;
    @Inject AuditRecorder audit;

    /** Bounds list()'s own per-request fan-out (Copilot finding): unbounded virtual-thread dispatch
     *  could otherwise drive as many concurrent liveDocumentCount calls and admin-pool writes as
     *  there are visible SearchIndex declarations — the same failure mode {@code
     *  SearchProjectionWorker#maxConcurrentRebuilds} exists to prevent for rebuilds, here scoped to
     *  one request rather than deployment-wide. */
    @ConfigProperty(name = "lumnik.search.list-fanout-concurrency", defaultValue = "8")
    int listFanoutConcurrency;

    @GET
    @Path("/index")
    public List<Map<String, Object>> list() {
        long tenant = TenantContextFilter.current();
        Map<String, SearchProjectionState> states = stateStore.findAllForTenant(tenant);
        Map<String, Instant> oldestMarks = markStore.oldestPendingMarkedAtForTenant(tenant);
        // Narrow transaction (Copilot finding): indexStore.list() is the only call here needing the
        // default datasource's JTA transaction (its own TxType.MANDATORY) — scoped to just this read
        // rather than held across the live provider round trips and admin-pool writes dispatched
        // below, none of which touch it (sinkGate/stateStore live on the admin datasource, JTA
        // enlistment disabled) — holding it that long would pin a pooled default-datasource
        // connection for the whole request.
        List<SearchIndexStore.Summary> visible = QuarkusTransaction.requiringNew().call(() ->
            indexStore.list(tenant).stream()
                .filter(s -> ScopeGrants.allowsAny(s.entityScopes()))
                .toList());

        // Each entry's own body() — a live provider round trip plus a cache write (code review
        // finding) — is independent of every other, so dispatched concurrently on virtual threads
        // (SearchProjectionWorker.rebuildExecutor's own idiom) rather than serialized in this one
        // request, where one slow/hanging index would otherwise block every index listed after it.
        // A Semaphore bounds how many run their actual live call/write at once (Copilot finding):
        // every task is submitted up front (virtual threads are cheap to park), but only
        // listFanoutConcurrency of them hold a permit and do real work at any given moment.
        Semaphore permits = new Semaphore(listFanoutConcurrency);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Map<String, Object>>> futures = visible.stream()
                .map(s -> executor.submit(() -> {
                    permits.acquire();
                    try {
                        return body(tenant, s.name(), s.status(), s.invalidReason(), s.withheld(),
                            states.get(s.name()), oldestMarks.get(s.name()));
                    } finally {
                        permits.release();
                    }
                }))
                .toList();
            return futures.stream().map(SearchOperationsResource::join).toList();
        }
    }

    private static Map<String, Object> join(Future<Map<String, Object>> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    @GET
    @Path("/index/{name}/status")
    public Response status(@PathParam("name") String name) {
        return withDeclaration(name, decl -> {
            long tenant = TenantContextFilter.current();
            SearchProjectionState state = stateStore.find(tenant, name).orElse(null);
            Instant oldestMark = markStore.oldestPendingMarkedAtForTenant(tenant).get(name);
            Map<String, Object> body = body(tenant, name, decl.status(), decl.invalidReason(), decl.withheld(),
                state, oldestMark);
            body.put("lastError", state == null ? null : state.lastError());
            body.put("activeRun", activeRunBody(state));
            return Response.ok(body).build();
        });
    }

    @POST
    @Path("/index/{name}/rebuild")
    public Response rebuild(@PathParam("name") String name) {
        return withDeclaration(name, decl -> {
            long tenant = TenantContextFilter.current();
            if ("invalid".equals(decl.status())) {
                return ApiErrors.conflict("index invalid", "invalidReason", decl.invalidReason());
            }

            var capability = searchGate.capability();
            if (capability.state() != SearchAvailability.AVAILABLE) {
                return capability.unavailableResponse(null);
            }

            RebuildClaim claim;
            try {
                // Parsing lives inside this same try (code review finding): a stored manifest_yaml
                // that no longer parses (parser-version skew, or an invalid reason
                // SearchIndexDefinitionCheck doesn't track) is exactly the kind of technical failure
                // this catch already maps to a 503 — parsing it outside would let it escape as a bare
                // 500 instead (design doc §4).
                SearchIndexManifest manifest = new SearchIndexManifestParser().parse(decl.manifestYaml());
                var authorizedSnapshot = new SearchProjectionWorker.SearchIndexDeclaration(
                    decl.status(), decl.entity(), manifest);
                claim = worker.triggerRebuild(tenant, name, authorizedSnapshot);
            } catch (RuntimeException e) {
                // A genuine technical failure (e.g. RunLock.tryAcquire's own SQLException, or the
                // rebuildExecutor rejecting the submit) — never surfaced to the caller as a bare 500
                // (design doc §4). worker/store keep throwing their own technical exception types; the
                // REST layer is the one place that maps them to the spec's mandated 503.
                return Response.status(503).type(MediaType.APPLICATION_JSON)
                    .entity(ApiErrors.error("search rebuild unavailable")).build();
            }
            return switch (claim) {
                case RebuildClaim.Started s -> {
                    audit.record("SearchIndex", name, "REBUILD", Map.of("runId", s.runId().toString()));
                    yield Response.status(202).entity(Map.of("runId", s.runId().toString())).build();
                }
                case RebuildClaim.RebuildAlreadyRunning r ->
                    ApiErrors.conflict("rebuild already in progress", "runId", r.runId().toString());
                case RebuildClaim.PairBusy ignored -> ApiErrors.conflict("index busy");
                case RebuildClaim.DeclarationChanged ignored -> ApiErrors.conflict("index declaration changed");
                case RebuildClaim.TooManyInFlight ignored -> ApiErrors.conflict("too many rebuilds in flight");
            };
        });
    }

    @POST
    @Path("/index/{name}/cancel")
    public Response cancel(@PathParam("name") String name) {
        return withDeclaration(name, decl -> {
            long tenant = TenantContextFilter.current();
            // Deliberately no SearchGate.capability() call here: cancel never depends on a provider
            // being installed — the run-tracking state row it acts on is written and read purely on
            // the hub's own admin datasource, so cancel remains provider-independent (Review Focus).
            CancelResult result;
            try {
                result = stateStore.requestCancel(tenant, name);
            } catch (RuntimeException e) {
                // Same posture as rebuild()'s own catch: requestCancel throws IllegalStateException for a
                // genuine connection/transaction failure, never for a legitimate "nothing to cancel" —
                // those are CancelResult values, not exceptions.
                return Response.status(503).type(MediaType.APPLICATION_JSON)
                    .entity(ApiErrors.error("search cancel unavailable")).build();
            }
            return switch (result) {
                case CancelResult.Requested r -> {
                    audit.record("SearchIndex", name, "CANCEL", Map.of("runId", r.runId().toString()));
                    yield Response.status(202).entity(Map.of("runId", r.runId().toString())).build();
                }
                case CancelResult.NoActiveRun ignored -> ApiErrors.conflict("no rebuild in progress");
                case CancelResult.PastCancellablePoint p -> ApiErrors.conflict(
                    "rebuild already past its cancellable point", "runId", p.runId().toString());
            };
        });
    }

    /** The {@code 404}/{@code 403} guard {@link #status}, {@link #rebuild} and {@link #cancel} all
     *  open with — looked up once, then handed to the route's own action. {@link #list} omits the
     *  403 by design (Review Focus: it filters instead of refusing) so it does not share this. */
    private Response withDeclaration(String name, Function<SearchIndexStore.Declaration, Response> action) {
        long tenant = TenantContextFilter.current();
        // Narrow transaction (Copilot finding, same fix as list()): indexStore.get() is the only call
        // here needing the default datasource's JTA transaction (its own TxType.MANDATORY) — scoped to
        // just this lookup, never held across `action`'s own slow work (a live provider round trip in
        // status(), worker.triggerRebuild in rebuild(), stateStore.requestCancel in cancel() — all on
        // the admin datasource, JTA enlistment disabled).
        Optional<SearchIndexStore.Declaration> decl =
            QuarkusTransaction.requiringNew().call(() -> indexStore.get(tenant, name));
        if (decl.isEmpty()) return ApiErrors.notFound("no search index named '" + name + "'");
        if (!ScopeGrants.allowsAny(decl.get().entityScopes())) return ApiErrors.scopeNotGranted();
        return action.apply(decl.get());
    }

    /** The fields every route shares (design doc §4). documentCount is built fresh on every call —
     *  never just echoed from the cache. {@code tenant} travels as a parameter, not read from
     *  {@link TenantContextFilter#current()}, since {@link #list} dispatches this on a worker thread
     *  (code review finding) where that request-bound {@code ThreadLocal} is never set. */
    private Map<String, Object> body(long tenant, String name, String indexStatus, String invalidReason,
                                     boolean withheld, SearchProjectionState state, Instant oldestMark) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        // Review Focus: no row yet == awaiting first build. insertBuilding/transitionToBuilding always
        // set active_run_id in the SAME statement as status='building', so a genuine in-flight build
        // always has a non-null activeRun; this synthetic "building" (no row) never does. `status()`
        // exposes activeRun for exactly this reason (code review finding) — a caller must check it,
        // not status alone, to tell "queued" from "actually running"; list() deliberately omits
        // activeRun (§4) and so cannot make that distinction, by the same design.
        body.put("status", state == null ? "building" : state.status());
        body.put("liveGeneration", state == null ? null : state.liveGeneration());

        DocumentCount dc = resolveDocumentCount(tenant, name, state);
        body.put("documentCount", dc.count());
        body.put("documentCountStale", dc.stale());
        body.put("documentCountAsOf", dc.asOf());

        body.put("lagSeconds", oldestMark == null ? 0 : Math.max(0, Instant.now().getEpochSecond() - oldestMark.getEpochSecond()));
        body.put("lastRebuild", lastRebuildBody(state));
        body.put("invalid", "invalid".equals(indexStatus));
        body.put("invalidReason", invalidReason);
        body.put("withheld", withheld);
        return body;
    }

    private record DocumentCount(Long count, boolean stale, Instant asOf) {}

    /** §4's own table: a fresh live call wins and refreshes the cache; a failure or an absent
     *  provider falls back to whatever is cached, or to the explicit null/stale/null "nothing to
     *  show" shape — never a fabricated 0. */
    private DocumentCount resolveDocumentCount(long tenant, String name, SearchProjectionState state) {
        Optional<ProjectionSink> sink = sinkGate.resolve();
        // Read BEFORE the live count call (code review finding): this is the generation the write
        // below is bound to, so a cutover landing between this read and the write is refused
        // rather than stamping a stale count onto the row's new generation.
        String expectedLiveGeneration = state == null ? null : state.liveGeneration();
        // Skip the live call entirely when nothing is published yet (Copilot finding): liveDocumentCount
        // is documented as "the published generation's own count — never staging, never an estimate",
        // so calling it with no live generation misuses the contract and can only fail or time out —
        // the cache/null fallback below is already the correct answer for that case.
        //
        // ALSO skip while a cutover is actually in flight (code review finding): sink.publish(gen)
        // runs BEFORE commitMarkCleanupAndMarkReady moves live_generation forward, so a live call
        // that starts once active_run_phase is 'publishing' can return the NEW generation's count
        // while live_generation still reads the OLD one — with no DB-visible signal at read time
        // that a cutover already happened at the provider. The generation-CAS below can only catch
        // this once the column itself has moved; the write-time phase check added there closes the
        // rest of the window (a call that started during 'building' but whose write lands after the
        // pair has moved into 'publishing').
        boolean cutoverInFlight = state != null && "publishing".equals(state.activeRunPhase());
        if (sink.isPresent() && expectedLiveGeneration != null && !cutoverInFlight) {
            try {
                long fresh = sink.get().liveDocumentCount(tenant, name);
                // The cache write DOUBLES as the generation/phase consistency check (Copilot finding
                // + code review finding): its return tells us whether a cutover landed between
                // reading expectedLiveGeneration and this live call — `false` means `fresh` may
                // belong to a generation other than the one this response reports, so it must not
                // be served as this generation's count. This is no longer a mere best-effort cache
                // refresh, so a write failure must FAIL CLOSED here (code review finding): we cannot
                // assume the check would have passed, only that it could not run — treated exactly
                // like a cutover having happened, falling through to the cache/null shape below.
                boolean stillCurrentGeneration;
                try {
                    stillCurrentGeneration = stateStore.updateDocumentCount(tenant, name, fresh, expectedLiveGeneration);
                } catch (RuntimeException ignored) {
                    stillCurrentGeneration = false;
                }
                if (stillCurrentGeneration) {
                    return new DocumentCount(fresh, false, Instant.now());
                }
                // else: a cutover raced this call, or the check itself could not run — fall through
                // to the cache/null shape below, exactly like a failed live call.
            } catch (RuntimeException ignored) {
                // the live call itself failed — fall through to the cache
            }
        }
        if (state != null && state.lastDocumentCount() != null) {
            return new DocumentCount(state.lastDocumentCount(), true, state.lastDocumentCountAt());
        }
        return new DocumentCount(null, true, null);
    }

    private Map<String, Object> activeRunBody(SearchProjectionState state) {
        if (state == null || state.activeRunId() == null) return null;
        return Map.of("runId", state.activeRunId().toString(), "phase", state.activeRunPhase(),
            "startedAt", state.activeRunStartedAt().toString());
    }

    private Map<String, Object> lastRebuildBody(SearchProjectionState state) {
        if (state == null || state.lastRebuildRunId() == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", state.lastRebuildRunId().toString());
        m.put("outcome", state.lastRebuildOutcome());
        m.put("finishedAt", state.lastRebuildFinishedAt().toString());
        return m;
    }
}
