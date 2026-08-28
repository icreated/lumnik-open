package io.lumnik.hub.connector.rest.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lumnik.hub.connector.api.ItemReader;
import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.rest.RestHttpClient;
import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;
import io.lumnik.hub.connector.rest.auth.AuthProvider;
import io.lumnik.hub.connector.rest.auth.AuthProviderRegistry;
import io.lumnik.hub.connector.rest.auth.AuthSpec;
import io.lumnik.hub.connector.rest.pagination.EndpointConfig;
import io.lumnik.hub.connector.rest.pagination.JsonPathExtractor;
import io.lumnik.hub.connector.rest.pagination.PaginationFactory;
import io.lumnik.hub.connector.rest.pagination.PaginationStrategy;
import io.lumnik.hub.connector.rest.RequestHeaders;
import io.lumnik.hub.connector.rest.reliability.GraphQlResponseValidator;
import io.lumnik.hub.connector.rest.reliability.NoopResponseValidator;
import io.lumnik.hub.connector.rest.reliability.RateLimiter;
import io.lumnik.hub.connector.rest.reliability.RateLimiterRegistry;
import io.lumnik.hub.connector.rest.reliability.ResponseValidator;
import io.lumnik.hub.connector.rest.reliability.RetryExecutor;
import io.lumnik.hub.connector.rest.reliability.RetryPolicy;
import io.lumnik.hub.connector.rest.soap.SoapFaultValidator;
import io.lumnik.hub.connector.rest.soap.XmlBodyParser;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ConnectorEngine bridge reader for {@code rest-generic} connectors.
 *
 * <p>This reader chunks at page granularity: each {@code read()} follows the pagination chain only
 * until the accumulated items reach {@code chunkSize} (finishing the page that crosses the
 * boundary — a page is never split), emits them as a {@link ReadResult.HasMore}, and carries the
 * next-page request in the cursor so the following {@code read()} — a fresh reader instance —
 * resumes where this one stopped. Heap per chunk is bounded by the chunk size, not by the size of
 * the customer's source. {@code watermark} mode shares this same page-window loop (see below for
 * how it stays safe under chunking); only one path keeps the older one-endpoint-one-chunk single
 * fetch:
 * <ul>
 *   <li>{@code soap} pagination — the strategy keeps its offset and render context inside the
 *       instance ({@code SoapPaginationStrategy#first} captures them), state a fresh reader on the
 *       next chunk cannot rebuild from a cursor.</li>
 * </ul>
 *
 * <h2>Cursor shape &amp; cross-run semantics</h2>
 * The exhausted (or single-fetch) cursor is the legacy two-field shape:
 * {@code {"watermark": <string|null>, "emittedByRun": "<runId|null>"}}. Mid-pagination it gains
 * {@code "resume"} (the next-page request: {@code method}, {@code url}, {@code query}, optional
 * {@code body}, and at most the header-bound pagination token under {@code headers}), {@code
 * "pages"} (pages fetched so far, for the run-wide {@link #MAX_PAGES} cap), and — watermark mode
 * only — {@code "watermarkCandidate"} (see below). Auth and declarative headers are deliberately
 * NOT persisted — they are re-derived per page, and writing them into
 * {@code connector.connector_cursor} would put secrets at rest. The engine persists the cursor
 * keyed by endpoint and reads it back at the START of every run (it survives across runs). The
 * reader is created fresh per chunk, so no in-reader state survives; "already emitted in THIS run"
 * is discriminated by {@code emittedByRun} vs {@code ctx.runId()}:
 * <ul>
 *   <li>{@code emittedByRun} equals this run's id and there is no {@code resume} → pagination
 *       already finished → {@link ReadResult.EndOfStream}.</li>
 *   <li>{@code emittedByRun} equals this run's id and {@code resume} is present → continue the
 *       pagination from that request. An unusable resume state fails loudly (never a silent
 *       restart from page 1).</li>
 *   <li>otherwise → start from the first page, ignoring any leftover {@code resume} from a run
 *       that died mid-pagination. A new run always has a fresh runId, so FULL re-runs re-read
 *       everything every run (idempotent via the {@code unique_key} upsert), and a WATERMARK
 *       re-run filters from whatever {@code watermark} the dead run left behind — see below.</li>
 * </ul>
 *
 * <h2>Incremental</h2>
 * {@code incremental.mode} (default {@code full}):
 * <ul>
 *   <li>{@code full} — no watermark; re-reads everything each run.</li>
 *   <li>{@code watermark} — the stored watermark is sent on the first request as the query param
 *       named by {@code incremental.param} (e.g. {@code ?updated_since=<watermark>}); the new
 *       watermark is the MAX value of {@code incremental.watermark_path} (a JsonPath) across the
 *       fetched items (string compare; ISO timestamps / monotonic ids). No items → keep prior.
 *       <p><b>Staged promotion under chunking.</b> {@code watermark} is what the NEXT run's first
 *       request filters by, so it must not move until pagination for THIS run is fully committed:
 *       a watermark persisted mid-pagination could make the next run start past pages not yet
 *       fetched — silent data loss. So {@code watermark} keeps its PRE-RUN value across every
 *       mid-run cursor; a second field, {@code watermarkCandidate}, carries the running MAX over
 *       items committed so far and grows every chunk. Only the final chunk — pagination exhausted
 *       — promotes {@code watermarkCandidate} into {@code watermark} and emits the legacy
 *       two-field shape (no {@code resume}, no candidate). A run that dies mid-pagination leaves
 *       {@code watermark} at its old value; the next run (a fresh runId, so its own {@code resume}
 *       is ignored per the bullet above) re-reads from that old point — rows may be re-read, they
 *       can never be skipped, and duplicates are absorbed by the existing {@code _row_hash} /
 *       {@code ON CONFLICT} upsert.</li>
 *   <li>{@code api_cursor} — NOT IMPLEMENTED (deferred). The pagination strategies consume the
 *       opaque next-cursor inside {@link PaginationStrategy#next} and do not expose a resumable
 *       cursor after the loop ends, so wiring it cleanly needs more than a watermark-style param.
 *       For now an {@code api_cursor} mode is treated as {@code full}.</li>
 * </ul>
 *
 * <p>Reuses the shared REST components (auth/pagination/rate-limit/retry/http); the cursor is persisted
 * in {@code connector.connector_cursor} (keyed by endpoint) — the single source of truth for REST runs.
 */
public class RestEndpointReader implements ItemReader<Map<String, Object>> {

    /** Caps the TOTAL pages across the whole run — {@code pages} in the cursor carries the count between chunks. */
    private static final int MAX_PAGES = 10_000;
    private static final String WATERMARK = "watermark";
    private static final String WATERMARK_CANDIDATE = "watermarkCandidate";
    private static final String EMITTED_BY_RUN = "emittedByRun";
    private static final String RESUME = "resume";
    private static final String PAGES = "pages";
    private static final String METHOD = "method";
    private static final String URL = "url";
    private static final String QUERY = "query";
    private static final String BODY = "body";
    private static final String HEADERS = "headers";

    /**
     * The endpoint row, resolved by {@link RestConnectorType#reader} at construction time — which
     * the engine runs inside a transaction. {@code read()} itself is called with NO transaction
     * (the source round-trip must not hold a pooled connection), and a repository lookup there
     * would find nothing: the Agroal interceptor only sets {@code app.current_tenant} on a JTA
     * connection acquire, so an RLS-enforced datasource would hide the row. Null when the row does
     * not exist — {@code read()} refuses loudly rather than reading nothing.
     */
    private final ConnectorEndpoint endpoint;
    private final AuthProviderRegistry authReg;
    private final PaginationFactory paginationFactory;
    private final RateLimiterRegistry rateRegistry;
    private final RestHttpClient http;
    private final ObjectMapper mapper;

    public RestEndpointReader(ConnectorEndpoint endpoint,
                              AuthProviderRegistry authReg,
                              PaginationFactory paginationFactory,
                              RateLimiterRegistry rateRegistry,
                              RestHttpClient http,
                              ObjectMapper mapper) {
        this.endpoint = endpoint;
        this.authReg = authReg;
        this.paginationFactory = paginationFactory;
        this.rateRegistry = rateRegistry;
        this.http = http;
        this.mapper = mapper;
    }

    /**
     * Resolve, decide whether this run already finished, build the request to start from, drive the
     * page loop, then hand the batch back with a cursor claiming this run. Full and watermark modes
     * both stop at the page that crosses {@code chunkSize} and resume from the cursor; SOAP keeps
     * the single fetch (see the class javadoc for why it cannot chunk).
     * (S3776: the count was the number of things assembled before the loop could start.)
     */
    @Override
    public ReadResult<Map<String, Object>> read(int chunkSize, ConnectorCursor cursor, ConnectorContext ctx) {
        Parsed parsed = parse(cursor);
        String runId = ctx.runId().toString();

        // This run already finished its pagination → finalize. (A mid-pagination cursor from THIS
        // run carries a resume state and falls through to the page-window loop below.)
        if (parsed.emittedByRun != null && parsed.emittedByRun.equals(runId) && parsed.resume == null) {
            return new ReadResult.EndOfStream<>(List.of());
        }

        Map<String, Object> cfg = ctx.config();

        // Endpoint method + path live on the endpoint entity, not in the merged config.
        ConnectorEndpoint ep = endpoint;
        if (ep == null) throw new IllegalStateException("Endpoint not found: " + ctx.endpointId());

        Incremental inc = incremental(cfg);
        Map<String, Object> pagCfg = mapConfig(cfg, "pagination");
        PaginationStrategy pag = paginationFactory.build(pagCfg);
        AuthProvider auth = authReg.build(toAuthSpec(mapConfig(cfg, "auth")));
        auth.probe();

        RateLimiter rl = rateRegistry.forConnector(ctx.connectorId(),
            (String) cfg.getOrDefault("default_rate_limit", "100/s"));
        RetryExecutor retry = new RetryExecutor(RetryPolicy.defaults(), validatorFor(cfg));
        BodyParser bodyParser = bodyParserFor(cfg);

        if ("soap".equals(str(pagCfg.get("kind")))) {
            // The single-fetch path: SOAP cannot chunk (its resume state lives inside the strategy
            // instance, unrebuildable from a cursor). It still commits the watermark over the whole
            // fetch when incremental is on — dropping it to null (as an earlier PR-B revision did)
            // made every SOAP+watermark run re-fetch the same window forever. Computed over ALL
            // items exactly as the pre-PR-B single fetch did, since a SOAP fetch is never partial.
            RestRequest req = firstRequest(pag, cfg, ep, inc, parsed);
            List<Map<String, Object>> all = fetchAllPages(auth, rl, retry, pag, req, bodyParser);
            // maxWatermark carries the prior watermark through when the path is null or no item
            // matches (see its own guard), so a path-less watermark misconfig preserves the prior
            // value instead of nulling it — matching the chunking branch and pre-PR-B single fetch.
            // No extra path!=null guard here: adding one reintroduced the re-fetch-forever drop.
            String wm = inc.enabled() ? maxWatermark(all, inc.path(), parsed.watermark) : null;
            return new ReadResult.HasMore<>(all, cursor(wm, runId));
        }

        // Full AND watermark modes share the page-granular chunk loop. A resume cursor from THIS
        // run continues the chain; one left behind by a dead run is ignored — a new run always
        // starts from page 1 (FULL re-reads everything; watermark filters by whatever `watermark`
        // that dead run left behind, since it never got to promote its candidate).
        boolean resuming = parsed.resume != null && runId.equals(parsed.emittedByRun);
        RestRequest start = resuming ? resumeRequest(parsed.resume, cfg, ctx)
                                     : firstRequest(pag, cfg, ep, inc, parsed);
        PageWindow window = fetchPageWindow(auth, rl, retry, pag, start, bodyParser,
            chunkSize, resuming ? parsed.pages : 0);

        if (inc.enabled()) {
            // Staged promotion — see the class javadoc. `watermark` is carried through unchanged
            // (never read from `window`, only from `parsed`); the only value that grows here is
            // the candidate, seeded from the previous chunk's candidate (or, on the first chunk,
            // from the pre-run watermark itself).
            String priorCandidate = resuming ? parsed.watermarkCandidate : parsed.watermark;
            String candidate = inc.path() != null
                ? maxWatermark(window.items(), inc.path(), priorCandidate) : priorCandidate;
            return new ReadResult.HasMore<>(window.items(),
                watermarkCursor(runId, parsed.watermark, candidate, window.next(), window.pages(),
                    cursorHeaderName(pagCfg)));
        }

        return new ReadResult.HasMore<>(window.items(),
            fullCursor(runId, window.next(), window.pages(), cursorHeaderName(pagCfg)));
    }

    /** The incremental block, reduced to what the runtime honours; {@code api_cursor} is deferred → full. */
    private record Incremental(boolean enabled, String param, String path) {}

    private static Incremental incremental(Map<String, Object> cfg) {
        Map<String, Object> inc = mapConfig(cfg, "incremental");
        String mode = String.valueOf(inc.getOrDefault("mode", "full"));
        return new Incremental(WATERMARK.equals(mode), (String) inc.get("param"), (String) inc.get("watermark_path"));
    }

    /**
     * Declarative headers first, then the stored watermark. Pagination preserves headers across pages
     * (withQuery/withBody copy the header map) and auth is applied per page on top, so auth headers
     * win on a name clash.
     */
    private static RestRequest firstRequest(PaginationStrategy pag, Map<String, Object> cfg,
                                            ConnectorEndpoint ep, Incremental inc, Parsed parsed) {
        EndpointConfig ec = new EndpointConfig((String) cfg.get("base_url"), ep.getMethod(), ep.getPath(), cfg);
        RestRequest req = RequestHeaders.apply(pag.first(ec, null), cfg.get(HEADERS), RequestHeaders.ENV);
        boolean canSend = inc.enabled() && parsed.watermark != null && inc.param() != null;
        return canSend ? req.withQuery(inc.param(), parsed.watermark) : req;
    }

    /**
     * GraphQL returns 200 + errors[], so the GraphQL validator is selected for anything MEANT to be
     * GraphQL (kind or query) — a malformed kind:graphql config that lost its query then still fails
     * loud instead of silently ingesting an empty batch.
     */
    private static ResponseValidator validatorFor(Map<String, Object> cfg) {
        if (isSoapEndpoint(cfg)) return new SoapFaultValidator();
        if (isGraphQlEndpoint(cfg)) return new GraphQlResponseValidator();
        return new NoopResponseValidator();
    }

    /**
     * SOAP responses are XML; convert to a JSON tree so pagination and items read it like any other.
     * The {@code response_path} hint drives the force-list (single element → singleton list).
     */
    private static BodyParser bodyParserFor(Map<String, Object> cfg) {
        if (!isSoapEndpoint(cfg)) return JsonBodyParser.INSTANCE;
        return new XmlBodyParser(str(mapConfig(cfg, "pagination").get("response_path")));
    }

    /**
     * An optional config sub-block. ABSENT is legitimate and defaults to empty; PRESENT-but-not-a-map
     * is a misconfiguration and must stay loud — {@code pagination: "foo"} would otherwise quietly
     * page not at all, and {@code RestConnectorValidator}'s refusal of a scalar {@code incremental}
     * would lose the runtime backstop it counts on for configs written straight to the DB, bypassing
     * apply. This used to be a bare cast, i.e. a ClassCastException; the message is the only change.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapConfig(Map<String, Object> cfg, String key) {
        Object v = cfg.get(key);
        if (v == null) return Map.of();
        if (v instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw new ConnectorException(
            "config block '" + key + "' must be an object, not a scalar: " + v);
    }

    /**
     * Whether an endpoint is meant to be GraphQL — {@code kind: graphql} OR an endpoint-level
     * {@code query}. Mirrors the validator's detection so the GraphQL response validator (and its
     * envelope/errors checks) is selected even for a malformed config that declares graphql but
     * lost its query; using only {@code query} would silently fall back to the Noop validator.
     */
    static boolean isGraphQlEndpoint(Map<String, Object> cfg) {
        return "graphql".equals(cfg.get("kind")) || cfg.get(QUERY) != null;
    }

    /** Whether an endpoint is a SOAP request-shape — authoritative flag {@code kind: soap}. */
    static boolean isSoapEndpoint(Map<String, Object> cfg) {
        return "soap".equals(cfg.get("kind"));
    }

    /** Follow the pagination chain (signing + rate-limiting + retrying each request) up to MAX_PAGES. */
    private List<Map<String, Object>> fetchAllPages(AuthProvider auth, RateLimiter rl, RetryExecutor retry,
                                                    PaginationStrategy pag, RestRequest first,
                                                    BodyParser bodyParser) {
        List<Map<String, Object>> all = new ArrayList<>();
        RestRequest req = first;
        int pages = 0;
        try {
            while (req != null && pages < MAX_PAGES) {
                RestRequest signed = auth.apply(req);
                rl.acquire();
                final RestRequest finalReq = signed;
                RestResponse resp = retry.execute(() -> http.execute(finalReq));
                RestHttpClient.refuseRedirect(resp);   // NEVER-followed 3xx must not read as an empty page
                resp = bodyParser.reparse(resp);   // SOAP: XML→JSON; JSON/GraphQL: identity
                all.addAll(pag.items(resp));
                req = pag.next(resp, signed);
                pages++;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ConnectorException("REST read interrupted", ie);
        } catch (Exception e) {
            throw (e instanceof RuntimeException re) ? re : new RuntimeException(e);
        }
        return all;
    }

    /** One chunk's worth of pages, plus what the next chunk needs: the next request (null = exhausted) and the run-wide page count. */
    private record PageWindow(List<Map<String, Object>> items, RestRequest next, int pages) {}

    /**
     * The shared full/watermark chunk loop: follow the pagination chain (signing + rate-limiting +
     * retrying each request) until the accumulated items reach {@code chunkSize} — the page that
     * crosses the boundary is finished, never split — pagination ends, or the run-wide
     * {@link #MAX_PAGES} cap is hit (which finalizes the run, mirroring the old loop's
     * stop-at-MAX_PAGES). The request threaded to {@link PaginationStrategy#next} is the UNSIGNED
     * one: it is what the resume cursor persists, and a persisted request must never carry
     * credentials — auth is re-applied per page anyway, so nothing is lost.
     */
    private PageWindow fetchPageWindow(AuthProvider auth, RateLimiter rl, RetryExecutor retry,
                                       PaginationStrategy pag, RestRequest first, BodyParser bodyParser,
                                       int chunkSize, int pagesAlreadyFetched) {
        List<Map<String, Object>> items = new ArrayList<>();
        RestRequest req = first;
        int pages = pagesAlreadyFetched;
        try {
            while (req != null && pages < MAX_PAGES && items.size() < chunkSize) {
                RestRequest signed = auth.apply(req);
                rl.acquire();
                final RestRequest finalReq = signed;
                RestResponse resp = retry.execute(() -> http.execute(finalReq));
                RestHttpClient.refuseRedirect(resp);   // NEVER-followed 3xx must not read as an empty page
                resp = bodyParser.reparse(resp);   // SOAP: XML→JSON; JSON/GraphQL: identity
                items.addAll(pag.items(resp));
                req = pag.next(resp, req);
                pages++;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ConnectorException("REST read interrupted", ie);
        } catch (Exception e) {
            throw (e instanceof RuntimeException re) ? re : new RuntimeException(e);
        }
        return new PageWindow(items, pages >= MAX_PAGES ? null : req, pages);
    }

    /** The header carrying the pagination token when the strategy binds its cursor to a header, else null. */
    private static String cursorHeaderName(Map<String, Object> pagCfg) {
        return "header".equals(str(pagCfg.get("cursor_binding"))) ? str(pagCfg.get("cursor_param")) : null;
    }

    /**
     * Rebuilds the next-page request from a resume cursor. Declarative headers are re-derived from
     * config and the header-bound pagination token (when persisted) is restored on top; auth is
     * re-applied per page by the loop. A resume state missing its method or url is unusable and
     * refused loudly — silently restarting from page 1 would double-ingest everything before it.
     */
    private static RestRequest resumeRequest(JsonNode resume, Map<String, Object> cfg, ConnectorContext ctx) {
        String method = text(resume, METHOD);
        String url = text(resume, URL);
        if (method == null || url == null) {
            throw new ConnectorException("Endpoint " + ctx.endpointId()
                + ": pagination resume state is unusable — re-run the connector to restart the extract");
        }
        Map<String, String> query = new HashMap<>();
        JsonNode q = resume.get(QUERY);
        if (q != null && q.isObject()) {
            q.fields().forEachRemaining(e -> query.put(e.getKey(), e.getValue().asText()));
        }
        RestRequest req = RequestHeaders.apply(new RestRequest(method, url, Map.of(), query, text(resume, BODY)),
            cfg.get(HEADERS), RequestHeaders.ENV);
        JsonNode h = resume.get(HEADERS);
        if (h != null && h.isObject()) {
            for (var it = h.fields(); it.hasNext(); ) {
                var e = it.next();
                req = req.withHeader(e.getKey(), e.getValue().asText());
            }
        }
        return req;
    }

    /**
     * Full-mode cursor: claims the run and, mid-pagination, carries the resume request and the
     * run-wide page count. Exhausted → the legacy two-field shape, so the next read ends the
     * stream. Headers are deliberately NOT persisted (auth and declarative headers are re-derived
     * per page — persisting them would write secrets into {@code connector.connector_cursor});
     * the one exception is the header-bound pagination token, which is resume state, not a secret.
     */
    private ConnectorCursor fullCursor(String runId, RestRequest next, int pages, String cursorHeader) {
        if (next == null) return cursor(null, runId);
        ObjectNode node = mapper.createObjectNode();
        node.putNull(WATERMARK);
        node.put(EMITTED_BY_RUN, runId);
        writeResume(node, next, pages, cursorHeader);
        return new ConnectorCursor(node, Instant.now());
    }

    /**
     * Watermark-mode cursor — the staged-promotion counterpart of {@link #fullCursor}. While
     * {@code next != null} (pagination not yet exhausted) this is a mid-run cursor: {@code
     * watermark} is written back UNCHANGED (the pre-run value {@code firstRequest} will read on the
     * next run) and {@code watermarkCandidate} carries the running max. There is no code path here
     * that writes {@code candidate} into the {@code watermark} field — promotion only happens once
     * pagination is exhausted, via the plain {@link #cursor(String, String)} legacy shape, which has
     * no candidate field at all. That is what makes early promotion structurally impossible: the
     * field {@code firstRequest} filters by and the field this method updates are never the same
     * field until exhaustion switches which cursor-builder runs.
     */
    private ConnectorCursor watermarkCursor(String runId, String watermark, String candidate,
                                            RestRequest next, int pages, String cursorHeader) {
        if (next == null) return cursor(candidate, runId);   // exhausted: promote candidate → watermark
        ObjectNode node = mapper.createObjectNode();
        if (watermark == null) node.putNull(WATERMARK); else node.put(WATERMARK, watermark);
        if (candidate == null) node.putNull(WATERMARK_CANDIDATE); else node.put(WATERMARK_CANDIDATE, candidate);
        node.put(EMITTED_BY_RUN, runId);
        writeResume(node, next, pages, cursorHeader);
        return new ConnectorCursor(node, Instant.now());
    }

    /** Shared resume-state serialization for the mid-pagination full and watermark cursors. */
    private static void writeResume(ObjectNode node, RestRequest next, int pages, String cursorHeader) {
        ObjectNode resume = node.putObject(RESUME);
        resume.put(METHOD, next.method());
        resume.put(URL, next.url());
        ObjectNode query = resume.putObject(QUERY);
        next.queryParams().forEach(query::put);
        if (next.body() != null) resume.put(BODY, next.body());
        if (cursorHeader != null && next.headers().containsKey(cursorHeader)) {
            resume.putObject(HEADERS).put(cursorHeader, next.headers().get(cursorHeader));
        }
        node.put(PAGES, pages);
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    /** MAX of {@code watermark_path} over the items (string compare); prior watermark if no items. */
    private String maxWatermark(List<Map<String, Object>> items, String path, String prior) {
        if (path == null || items.isEmpty()) return prior;
        String max = prior;
        for (Map<String, Object> item : items) {
            JsonNode node = mapper.valueToTree(item);
            Object v = JsonPathExtractor.read(node, path);
            if (v == null) continue;
            String s = String.valueOf(v);
            if (max == null || s.compareTo(max) > 0) max = s;
        }
        return max;
    }

    private record Parsed(String watermark, String watermarkCandidate, String emittedByRun,
                          JsonNode resume, int pages) {}

    private Parsed parse(ConnectorCursor cursor) {
        if (cursor == null || cursor.isEmpty()) return new Parsed(null, null, null, null, 0);
        JsonNode v = cursor.value();
        String wm = v.hasNonNull(WATERMARK) ? v.get(WATERMARK).asText() : null;
        String wmCandidate = v.hasNonNull(WATERMARK_CANDIDATE) ? v.get(WATERMARK_CANDIDATE).asText() : null;
        String run = v.hasNonNull(EMITTED_BY_RUN) ? v.get(EMITTED_BY_RUN).asText() : null;
        JsonNode resume = v.hasNonNull(RESUME) ? v.get(RESUME) : null;
        int pages = v.hasNonNull(PAGES) ? v.get(PAGES).asInt() : 0;
        return new Parsed(wm, wmCandidate, run, resume, pages);
    }

    private ConnectorCursor cursor(String watermark, String emittedByRun) {
        ObjectNode node = mapper.createObjectNode();
        if (watermark == null) node.putNull(WATERMARK); else node.put(WATERMARK, watermark);
        node.put(EMITTED_BY_RUN, emittedByRun);
        return new ConnectorCursor(node, Instant.now());
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    /** Builds the {@link AuthSpec} from the connector's auth config block. */
    private AuthSpec toAuthSpec(Map<String, Object> raw) {
        String kind = raw.containsKey("kind") ? String.valueOf(raw.get("kind")) : "none";
        Map<String, String> cfg = new HashMap<>();
        raw.forEach((k, v) -> {
            if (!"kind".equals(k) && v != null) cfg.put(k, String.valueOf(v));
        });
        return new AuthSpec(kind, cfg, List.of());
    }
}
