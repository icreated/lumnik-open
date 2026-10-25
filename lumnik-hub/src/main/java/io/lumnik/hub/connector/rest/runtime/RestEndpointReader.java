package io.lumnik.hub.connector.rest.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lumnik.hub.connector.api.ItemReader;
import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.hub.connector.api.types.RunScope;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.exceptions.RunCancelledException;
import io.lumnik.hub.connector.exceptions.SourceCallException;
import io.lumnik.hub.connector.rest.RestHttpClient;
import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;
import io.lumnik.hub.connector.rest.ResponseStatusGuard;
import io.lumnik.hub.connector.rest.auth.AuthProvider;
import io.lumnik.hub.connector.rest.auth.AuthProviderRegistry;
import io.lumnik.hub.connector.rest.auth.AuthSpec;
import io.lumnik.hub.connector.rest.pagination.EndpointConfig;
import io.lumnik.hub.connector.rest.pagination.JsonPathExtractor;
import io.lumnik.hub.connector.rest.pagination.PaginationFactory;
import io.lumnik.hub.connector.rest.pagination.PaginationStrategy;
import io.lumnik.hub.connector.rest.pagination.PaginationSupport;
import io.lumnik.hub.connector.rest.pagination.VariableCursorBinding;
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
import io.lumnik.hub.connector.secret.SecretRefs;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

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
 *       named by {@code incremental.param} (e.g. {@code ?updated_since=<watermark>}) — on a graphql
 *       endpoint, as the GraphQL variable of that name in the POST body; the new
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
    /** When this run began, on the hub's clock — mid-run cursors carry it so the last chunk knows the run's length. */
    private static final String RUN_STARTED_AT = "runStartedAt";
    /** How long the run that set the mark took: the next run asks from that far before it (#1397). */
    private static final String RUN_SECONDS = "runSeconds";
    /** Added to the run's length: clocks drift, and a change committed as the run ended may carry a later stamp. */
    private static final long LOOKBACK_MARGIN_SECONDS = 60;
    private static final Pattern LOOKBACK = Pattern.compile("(\\d{1,6})([smh])");
    private static final Pattern INTEGER_MARK = Pattern.compile("-?\\d+");
    /** The fraction of a second as the mark wrote it: {@code .378}, {@code .378123}, or none. */
    private static final Pattern FRACTION = Pattern.compile("T\\d{2}:\\d{2}:\\d{2}(?:\\.(\\d{1,9}))?");
    private static final String EMITTED_BY_RUN = "emittedByRun";
    private static final String RESUME = "resume";
    private static final String PAGES = "pages";
    private static final String EMITTED = "emitted";
    private static final String DECLARED_TOTAL = "declaredTotal";
    private static final String TOTAL_DISPUTED = "declaredTotalDisputed";
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
    /** What a header's {@code value_env} resolves to — the auth fields' rule: lm secret, then environment. */
    private final SecretRefs secrets;

    public RestEndpointReader(ConnectorEndpoint endpoint,
                              AuthProviderRegistry authReg,
                              PaginationFactory paginationFactory,
                              RateLimiterRegistry rateRegistry,
                              RestHttpClient http,
                              ObjectMapper mapper,
                              SecretRefs secrets) {
        this.endpoint = endpoint;
        this.authReg = authReg;
        this.paginationFactory = paginationFactory;
        this.rateRegistry = rateRegistry;
        this.http = http;
        this.mapper = mapper;
        this.secrets = secrets;
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
        Instant readStarted = now(ctx);

        // Endpoint method + path live on the endpoint entity, not in the merged config.
        ConnectorEndpoint ep = endpoint;
        if (ep == null) throw new IllegalStateException("Endpoint not found: " + ctx.endpointId());

        Incremental inc = incremental(cfg);
        Map<String, Object> pagCfg = mapConfig(cfg, "pagination");
        PaginationStrategy pag = paginationFactory.build(pagCfg);
        // Lifted out of the strategies for the same reason cursorHeaderName is: reading a JsonPath
        // out of a response body does not vary by pagination shape, so nothing here is per-strategy.
        String totalPath = str(pagCfg.get("total_path"));
        AuthProvider auth = authReg.build(authSpecFor(cfg, new AuthSpec.Owner(ctx.tenantId(), ctx.connectorId())));
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
            RestRequest req = firstRequest(pag, cfg, ep, inc, parsed, secrets::lookup);
            List<Map<String, Object>> all = fetchAllPages(auth, rl, retry, pag, req, bodyParser, ctx.scope());
            // maxWatermark carries the prior watermark through when the path is null or no item
            // matches (see its own guard), so a path-less watermark misconfig preserves the prior
            // value instead of nulling it — matching the chunking branch and pre-PR-B single fetch.
            // No extra path!=null guard here: adding one reintroduced the re-fetch-forever drop.
            String wm = inc.enabled() ? maxWatermark(all, inc.path(), parsed.watermark) : null;
            return new ReadResult.HasMore<>(all, cursor(wm, runId, secondsSince(readStarted, ctx)));
        }

        // Full AND watermark modes share the page-granular chunk loop. A resume cursor from THIS
        // run continues the chain; one left behind by a dead run is ignored — a new run always
        // starts from page 1 (FULL re-reads everything; watermark filters by whatever `watermark`
        // that dead run left behind, since it never got to promote its candidate).
        boolean resuming = parsed.resume != null && runId.equals(parsed.emittedByRun);
        Instant runStarted = resuming && parsed.runStartedAt != null ? parsed.runStartedAt : readStarted;
        RestRequest start = resuming ? resumeRequest(parsed.resume, cfg, ep, secrets::lookup)
                                     : firstRequest(pag, cfg, ep, inc, parsed, secrets::lookup);
        PageWindow window = fetchPageWindow(auth, rl, retry, pag, start, bodyParser,
            chunkSize, resuming ? parsed.pages : 0,
            resuming ? parsed.declaredTotal : DeclaredTotal.NONE, totalPath);

        // `resuming` gates the running tally for the same reason it gates `pages` above: a cursor
        // left behind by a DEAD run makes this run start from page 1, so its counts are not ours.
        long emitted = (resuming ? parsed.emitted : 0) + window.items().size();

        // Reconcile once, when pagination is exhausted — and only over a read that asked for
        // everything. The exemption is a NAMED condition rather than a consequence of where this
        // line sits, so the next mode that starts filtering has to answer it instead of inheriting
        // the guard by falling past a branch.
        if (!inc.filtersRows() && window.next() == null) {
            reconcileWithDeclaredTotal(ep, emitted, window.total());
        }

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
                    cursorHeaderName(pagCfg), runStarted, ctx));
        }

        return new ReadResult.HasMore<>(window.items(),
            fullCursor(runId, window, emitted, cursorHeaderName(pagCfg)));
    }

    /** The incremental block, reduced to what the runtime honours; {@code api_cursor} is deferred → full. */
    private record Incremental(boolean enabled, String param, String path, Duration lookback) {
        /**
         * Whether this read NARROWS what the source returns — the one case where the source's own
         * total is not a number this run can be measured against. {@code api_cursor} is deferred to
         * full and sends no filter, so it is deliberately not one; the mode that starts filtering
         * must say so here rather than inherit the answer.
         */
        boolean filtersRows() { return enabled; }
    }

    private static Incremental incremental(Map<String, Object> cfg) {
        Map<String, Object> inc = mapConfig(cfg, "incremental");
        String mode = String.valueOf(inc.getOrDefault("mode", "full"));
        return new Incremental(WATERMARK.equals(mode), (String) inc.get("param"), (String) inc.get("watermark_path"),
            lookback(inc.get("lookback")));
    }

    /** {@code incremental.lookback}: {@code 90s}, {@code 10m}, {@code 2h}, {@code 0s}; absent means "the run's own length". */
    public static Duration lookback(Object declared) {
        if (declared == null) return null;
        Matcher m = LOOKBACK.matcher(String.valueOf(declared).strip());
        if (!m.matches()) {
            throw new ConnectorException("incremental.lookback must be a number of s, m or h (e.g. 10m, 0s)");
        }
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            default -> Duration.ofHours(n);
        };
    }

    /**
     * Declarative headers first, then the stored watermark. Pagination preserves headers across pages
     * (withQuery/withBody copy the header map) and auth is applied per page on top, so auth headers
     * win on a name clash. On a graphql endpoint the mark is the GraphQL variable {@code param}: a
     * GraphQL server reads no URL parameter, and the relay loop keeps the body's variables page to page.
     */
    private static RestRequest firstRequest(PaginationStrategy pag, Map<String, Object> cfg,
                                            ConnectorEndpoint ep, Incremental inc, Parsed parsed,
                                            UnaryOperator<String> secrets) {
        RestRequest req = firstRequest(pag, cfg, ep, secrets);
        boolean canSend = inc.enabled() && parsed.watermark != null && inc.param() != null;
        if (!canSend) return req;
        String mark = sentWatermark(parsed.watermark, inc.lookback(), parsed.runSeconds);
        return isGraphQlEndpoint(cfg) ? new VariableCursorBinding(inc.param()).bind(req, mark)
                                      : req.withQuery(inc.param(), mark);
    }

    /**
     * What the next run asks from (#1397). A REST source pages in its own order: a row changed after its page was
     * read but before a later page carries a newer one falls behind the new mark, and no later run would ever ask
     * for it. Anything that changed during the run that set the mark is stamped at or after its start — at or
     * after {@code mark − run length} — so the run asks from there, plus a margin. Rows read again are upserted on
     * their key. Only an instant can be moved back, and it is written as the mark was — its offset, its fraction
     * width — so an API that reads its own format reads it; any other mark is sent as stored, and so is a
     * declared lookback of {@code 0s}. The stored mark never moves back: the window is applied here.
     */
    static String sentWatermark(String mark, Duration declared, Long runSeconds) {
        if (instantOf(mark) == null) return mark;
        Duration back = declared != null ? declared
            : Duration.ofSeconds((runSeconds == null ? 0 : runSeconds) + LOOKBACK_MARGIN_SECONDS);
        if (back.isZero()) return mark;
        Matcher fraction = FRACTION.matcher(mark);
        int digits = fraction.find() && fraction.group(1) != null ? fraction.group(1).length() : 0;
        String offset = mark.endsWith("Z") || mark.endsWith("z") ? "'Z'" : "xxx";
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss" + (digits > 0 ? "." + "S".repeat(digits) : "") + offset,
                Locale.ROOT)   // a wire format: ASCII digits whatever the JVM's locale
            .format(OffsetDateTime.parse(mark).minus(back));
    }

    /** The mark as an instant when it is one — with a zone or an offset — else null. */
    private static Instant instantOf(String mark) {
        try {
            return OffsetDateTime.parse(mark).toInstant();
        } catch (RuntimeException notAnInstant) {
            return null;
        }
    }

    /**
     * The very first request of a read, before anything a RUN alone knows (a stored watermark) is
     * added — method, path, query, body and declarative headers, exactly as the strategy composes
     * them. Shared with {@link RestPreflight}: a pre-flight that composed its own request would be
     * reporting on a door the run never knocks at.
     */
    static RestRequest firstRequest(PaginationStrategy pag, Map<String, Object> cfg, ConnectorEndpoint ep,
                                    UnaryOperator<String> secrets) {
        EndpointConfig ec = new EndpointConfig((String) cfg.get("base_url"), ep.getMethod(), ep.getPath(), cfg);
        return RequestHeaders.apply(pag.first(ec, null), cfg.get(HEADERS), secrets);
    }

    /**
     * GraphQL returns 200 + errors[], so the GraphQL validator is selected for anything MEANT to be
     * GraphQL (kind or query) — a malformed kind:graphql config that lost its query then still fails
     * loud instead of silently ingesting an empty batch.
     */
    static ResponseValidator validatorFor(Map<String, Object> cfg) {
        if (isSoapEndpoint(cfg)) return new SoapFaultValidator();
        if (isGraphQlEndpoint(cfg)) return new GraphQlResponseValidator();
        return new NoopResponseValidator();
    }

    /**
     * SOAP responses are XML; convert to a JSON tree so pagination and items read it like any other.
     * The {@code response_path} hint drives the force-list (single element → singleton list).
     */
    static BodyParser bodyParserFor(Map<String, Object> cfg) {
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
    static Map<String, Object> mapConfig(Map<String, Object> cfg, String key) {
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
    public static boolean isGraphQlEndpoint(Map<String, Object> cfg) {
        return "graphql".equals(cfg.get("kind")) || cfg.get(QUERY) != null;
    }

    /** Whether an endpoint is a SOAP request-shape — authoritative flag {@code kind: soap}. */
    public static boolean isSoapEndpoint(Map<String, Object> cfg) {
        return "soap".equals(cfg.get("kind"));
    }

    /**
     * One page request: signed by the credential, which may sign it again after a 401 (#1393), and each send
     * rate-limited and retried. The request handed in stays unsigned.
     */
    private RestResponse send(AuthProvider auth, RateLimiter rl, RetryExecutor retry, RestRequest req) throws Exception {
        return auth.send(req, signed -> {
            rl.acquire();
            return retry.execute(() -> http.exchange(signed, retry.validator()));
        });
    }

    /**
     * Follow the pagination chain (signing + rate-limiting + retrying each request) up to MAX_PAGES. The whole fetch
     * is one chunk, so the run's cancel is read here before each page, as the engine reads it before each chunk: a
     * cancelled fetch throws what it read away, never written (#1444).
     */
    private List<Map<String, Object>> fetchAllPages(AuthProvider auth, RateLimiter rl, RetryExecutor retry,
                                                    PaginationStrategy pag, RestRequest first,
                                                    BodyParser bodyParser, RunScope scope) {
        List<Map<String, Object>> all = new ArrayList<>();
        RestRequest req = first;
        int pages = 0;
        try {
            while (req != null && pages < MAX_PAGES) {
                if (scope.cancelRequested()) throw new RunCancelledException();
                RestResponse resp = send(auth, rl, retry, req);
                ResponseStatusGuard.refuseNonSuccess(resp);   // a non-2xx must never read as an empty page
                resp = bodyParser.reparse(resp);   // SOAP: XML→JSON; JSON/GraphQL: identity
                all.addAll(pag.items(resp));
                req = advancing(req, pag.next(resp, req));   // unsigned, as in fetchPageWindow: next never inherits auth
                pages++;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ConnectorException("REST read interrupted", ie);
        } catch (RuntimeException ours) {
            // Unchecked keeps its type and its text: the status guard's refusal, a pagination or
            // validator refusal, a hub defect.
            throw ours;
        } catch (Exception call) {
            // Checked is a call to the source that threw, after the retry executor spent its
            // attempts: named by its kind, never by its message, which can quote the source URL
            // (#1166). Wrapped HERE, not in RestHttpClient: the retry executor retries on the
            // IOException type, and the pre-flight and the probe tell an unreachable source from a
            // refusal by the same types — a client throwing a described exception would change all three.
            throw new SourceCallException(call);
        }
        return all;
    }

    /**
     * One chunk's worth of pages, plus what the next chunk needs: the next request (null =
     * exhausted), the run-wide page count, and what the source has claimed about its own size.
     */
    private record PageWindow(List<Map<String, Object>> items, RestRequest next, int pages,
                              DeclaredTotal total) {}

    /**
     * What the source has said about its own row count so far in this run, and whether it has
     * contradicted itself.
     *
     * <p>A total that MOVES between pages is not a defect — it is a live source: rows were inserted
     * or deleted while we paged. There is then no number the run can be measured against, so the
     * reconciliation abstains rather than fail a run that lost nothing. Refusing to judge what we
     * cannot model is the same rule the SQL guards follow; the alternative here is a false red on
     * every busy ERP.
     */
    private record DeclaredTotal(Long value, boolean disputed) {
        static final DeclaredTotal NONE = new DeclaredTotal(null, false);

        DeclaredTotal observe(OptionalLong seen) {
            if (seen.isEmpty()) return this;
            if (value == null) return new DeclaredTotal(seen.getAsLong(), disputed);
            return value == seen.getAsLong() ? this : new DeclaredTotal(value, true);
        }

        boolean usable() { return value != null && !disputed; }
    }

    /**
     * NEXT, unless it is the request just SENT: the same request brings the same page back, so asking
     * again only re-reads it up to {@link #MAX_PAGES} — a SOAP body with no paging placeholder, a
     * cursor the source repeats (#1387).
     */
    private static RestRequest advancing(RestRequest sent, RestRequest next) {
        return sent.equals(next) ? null : next;
    }

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
                                       int chunkSize, int pagesAlreadyFetched,
                                       DeclaredTotal carriedTotal, String totalPath) {
        List<Map<String, Object>> items = new ArrayList<>();
        RestRequest req = first;
        int pages = pagesAlreadyFetched;
        DeclaredTotal total = carriedTotal;
        try {
            while (req != null && pages < MAX_PAGES && items.size() < chunkSize) {
                RestResponse resp = send(auth, rl, retry, req);
                ResponseStatusGuard.refuseNonSuccess(resp);   // a non-2xx must never read as an empty page
                resp = bodyParser.reparse(resp);   // SOAP: XML→JSON; JSON/GraphQL: identity
                items.addAll(pag.items(resp));
                total = total.observe(PaginationSupport.declaredItems(resp.json(), totalPath));
                req = advancing(req, pag.next(resp, req));
                pages++;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ConnectorException("REST read interrupted", ie);
        } catch (RuntimeException ours) {
            throw ours;   // same split as fetchAllPages
        } catch (Exception call) {
            throw new SourceCallException(call);
        }
        return new PageWindow(items, pages >= MAX_PAGES ? null : req, pages, total);
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
    private static RestRequest resumeRequest(JsonNode resume, Map<String, Object> cfg, ConnectorEndpoint ep,
                                             UnaryOperator<String> secrets) {
        String method = text(resume, METHOD);
        String url = text(resume, URL);
        if (method == null || url == null) {
            throw new ConnectorException(named(ep)
                + ": pagination resume state is unusable — re-run the connector to restart the extract");
        }
        Map<String, String> query = new HashMap<>();
        JsonNode q = resume.get(QUERY);
        if (q != null && q.isObject()) {
            q.fields().forEachRemaining(e -> query.put(e.getKey(), e.getValue().asText()));
        }
        RestRequest req = RequestHeaders.apply(new RestRequest(method, url, Map.of(), query, text(resume, BODY)),
            cfg.get(HEADERS), secrets);
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
    private ConnectorCursor fullCursor(String runId, PageWindow window, long emitted, String cursorHeader) {
        if (window.next() == null) return cursor(null, runId, null);
        ObjectNode node = mapper.createObjectNode();
        node.putNull(WATERMARK);
        node.put(EMITTED_BY_RUN, runId);
        writeResume(node, window.next(), window.pages(), cursorHeader);
        // The reconciliation's run-wide state. It rides here for the same reason `pages` does: a
        // fresh reader is built per chunk, so only what the cursor carries survives into the next.
        // `emitted` counts rows RETURNED, and equals the run's records_in only while REST isolates
        // no malformed rows at read time — the day it does, these stop being the same number.
        node.put(EMITTED, emitted);
        node.put(DECLARED_TOTAL, window.total().value());   // a null Long lands as JSON null
        node.put(TOTAL_DISPUTED, window.total().disputed());
        return new ConnectorCursor(node, Instant.now());
    }

    /**
     * The source's own total, used as a WITNESS at the end of the read.
     *
     * <p>A pagination stop condition only ever says "no more pages" — never "you got everything you
     * were promised". So a read that lost rows ends the same way a complete one does, and the
     * conservation invariant downstream ({@code records_in = records_out + records_skipped}) is
     * self-consistent about a number that is already wrong. The source is the only witness outside
     * the run, and the strategies that page by offset already ask it for a total.
     *
     * <p>Refusing (rather than warning) is the same choice {@code ResponseStatusGuard} makes one
     * layer down: a run that reports success over a partial extract is the failure mode being
     * fought, and a green-but-short run is indistinguishable from "nothing changed upstream".
     */
    private static void reconcileWithDeclaredTotal(ConnectorEndpoint ep, long emitted, DeclaredTotal total) {
        if (!total.usable() || emitted == total.value()) return;
        throw new ConnectorException(named(ep) + ": the source declared "
            + total.value() + " rows but this run returned " + emitted
            + " — refusing to report a partial extract as a complete one");
    }

    /**
     * The endpoint as the manifest names it — the manifest's {@code id}, stored as {@code name}, not the
     * row's internal UUID: the name an integrator can look up (#1373).
     */
    private static String named(ConnectorEndpoint ep) {
        return "Endpoint '" + ep.getName() + "'";
    }

    /**
     * Watermark-mode cursor — the staged-promotion counterpart of {@link #fullCursor}. While
     * {@code next != null} (pagination not yet exhausted) this is a mid-run cursor: {@code
     * watermark} is written back UNCHANGED (the pre-run value {@code firstRequest} will read on the
     * next run) and {@code watermarkCandidate} carries the running max. There is no code path here
     * that writes {@code candidate} into the {@code watermark} field — promotion only happens once
     * pagination is exhausted, via the plain {@link #cursor(String, String, Long)} legacy shape, which has
     * no candidate field at all. That is what makes early promotion structurally impossible: the
     * field {@code firstRequest} filters by and the field this method updates are never the same
     * field until exhaustion switches which cursor-builder runs.
     */
    private ConnectorCursor watermarkCursor(String runId, String watermark, String candidate,
                                            RestRequest next, int pages, String cursorHeader,
                                            Instant runStarted, ConnectorContext ctx) {
        // exhausted: promote candidate → watermark, with the run's length the next run looks back by
        if (next == null) return cursor(candidate, runId, secondsSince(runStarted, ctx));
        ObjectNode node = mapper.createObjectNode();
        if (watermark == null) node.putNull(WATERMARK); else node.put(WATERMARK, watermark);
        if (candidate == null) node.putNull(WATERMARK_CANDIDATE); else node.put(WATERMARK_CANDIDATE, candidate);
        node.put(EMITTED_BY_RUN, runId);
        node.put(RUN_STARTED_AT, runStarted.toString());
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

    /**
     * MAX of {@code watermark_path} over the items; prior watermark if no items. Compared by what the values are
     * (#1397): two instants by time — offsets may differ — two integers by value, so {@code 10} passes {@code 9};
     * anything else as text.
     */
    private String maxWatermark(List<Map<String, Object>> items, String path, String prior) {
        if (path == null || items.isEmpty()) return prior;
        String max = prior;
        for (Map<String, Object> item : items) {
            Object v = JsonPathExtractor.readParsed(item, path);
            if (v == null) continue;
            String s = String.valueOf(v);
            if (max == null || compareMarks(s, max) > 0) max = s;
        }
        return max;
    }

    static int compareMarks(String a, String b) {
        Instant ia = instantOf(a);
        Instant ib = instantOf(b);
        if (ia != null && ib != null) return ia.compareTo(ib);
        if (INTEGER_MARK.matcher(a).matches() && INTEGER_MARK.matcher(b).matches()) {
            return compareIntegers(a, b);
        }
        return a.compareTo(b);
    }

    /**
     * Two whole numbers of any length compared by value without parsing them: sign, then digit count once leading
     * zeros are gone, then the digits. A source writes the mark, so its length is not ours to bound — and a
     * million-digit value must not cost a million-digit number per item.
     */
    private static int compareIntegers(String a, String b) {
        boolean negA = a.startsWith("-");
        boolean negB = b.startsWith("-");
        String da = stripZeros(negA ? a.substring(1) : a);
        String db = stripZeros(negB ? b.substring(1) : b);
        if (da.equals("0")) negA = false;
        if (db.equals("0")) negB = false;
        if (negA != negB) return negA ? -1 : 1;
        int magnitude = da.length() != db.length() ? Integer.compare(da.length(), db.length()) : da.compareTo(db);
        return negA ? -magnitude : magnitude;
    }

    private static String stripZeros(String digits) {
        int i = 0;
        while (i < digits.length() - 1 && digits.charAt(i) == '0') i++;
        return digits.substring(i);
    }

    private record Parsed(String watermark, String watermarkCandidate, String emittedByRun,
                          JsonNode resume, int pages, long emitted, DeclaredTotal declaredTotal,
                          Instant runStartedAt, Long runSeconds) {}

    private Parsed parse(ConnectorCursor cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return new Parsed(null, null, null, null, 0, 0, DeclaredTotal.NONE, null, null);
        }
        JsonNode v = cursor.value();
        String wm = v.hasNonNull(WATERMARK) ? v.get(WATERMARK).asText() : null;
        String wmCandidate = v.hasNonNull(WATERMARK_CANDIDATE) ? v.get(WATERMARK_CANDIDATE).asText() : null;
        String run = v.hasNonNull(EMITTED_BY_RUN) ? v.get(EMITTED_BY_RUN).asText() : null;
        JsonNode resume = v.hasNonNull(RESUME) ? v.get(RESUME) : null;
        int pages = v.hasNonNull(PAGES) ? v.get(PAGES).asInt() : 0;
        // Absent on a cursor written by an older hub: read as "nothing counted, nothing declared",
        // which is exactly the state that makes the reconciliation abstain instead of misfire.
        long emitted = v.hasNonNull(EMITTED) ? v.get(EMITTED).asLong() : 0;
        DeclaredTotal declaredTotal = new DeclaredTotal(
            v.hasNonNull(DECLARED_TOTAL) ? v.get(DECLARED_TOTAL).asLong() : null,
            v.hasNonNull(TOTAL_DISPUTED) && v.get(TOTAL_DISPUTED).asBoolean());
        Instant runStartedAt = v.hasNonNull(RUN_STARTED_AT) ? Instant.parse(v.get(RUN_STARTED_AT).asText()) : null;
        Long runSeconds = v.hasNonNull(RUN_SECONDS) ? v.get(RUN_SECONDS).asLong() : null;
        return new Parsed(wm, wmCandidate, run, resume, pages, emitted, declaredTotal, runStartedAt, runSeconds);
    }

    private ConnectorCursor cursor(String watermark, String emittedByRun, Long runSeconds) {
        ObjectNode node = mapper.createObjectNode();
        if (watermark == null) node.putNull(WATERMARK); else node.put(WATERMARK, watermark);
        node.put(EMITTED_BY_RUN, emittedByRun);
        if (runSeconds != null) node.put(RUN_SECONDS, runSeconds);
        return new ConnectorCursor(node, Instant.now());
    }

    private static Instant now(ConnectorContext ctx) {
        return (ctx.clock() != null ? ctx.clock() : Clock.systemUTC()).instant();
    }

    /** Whole seconds from {@code start} to now, rounded up: a window is never shorter than the run. */
    private static long secondsSince(Instant start, ConnectorContext ctx) {
        long millis = Math.max(0, Duration.between(start, now(ctx)).toMillis());
        return (millis + 999) / 1000;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    /**
     * The credential a source is approached with, read off an effective config. Public because it
     * is not the run's private business: the endpoint pre-flight and the REST probe must present
     * the same one, or they diagnose a source nobody talks to. The default when the block is
     * absent is {@code none} — never an empty bearer.
     *
     * <p>One entry point rather than two exported halves: {@code toAuthSpec(mapConfig(cfg,"auth"))}
     * was being spelled out at three call sites, so the {@code "auth"} key and the {@code none}
     * default travelled with each copy.
     */
    public static AuthSpec authSpecFor(Map<String, Object> cfg, AuthSpec.Owner owner) {
        return toAuthSpec(mapConfig(cfg, "auth"), owner);
    }

    /** Builds the {@link AuthSpec} from the connector's auth config block, for the connector it authenticates. */
    static AuthSpec toAuthSpec(Map<String, Object> raw, AuthSpec.Owner owner) {
        String kind = raw.containsKey("kind") ? String.valueOf(raw.get("kind")) : "none";
        Map<String, String> cfg = new HashMap<>();
        raw.forEach((k, v) -> {
            if (!"kind".equals(k) && v != null) cfg.put(k, String.valueOf(v));
        });
        return new AuthSpec(kind, cfg, List.of(), owner);
    }
}
