package io.lumnik.hub.connector.rest.runtime;

import io.lumnik.hub.connector.runtime.OwnedRunScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.hub.connector.api.types.SyncMode;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.rest.RestHttpClient;
import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;
import io.lumnik.hub.connector.rest.auth.AuthProvider;
import io.lumnik.hub.connector.rest.auth.AuthProviderRegistry;
import io.lumnik.hub.connector.rest.auth.AuthSpec;
import io.lumnik.hub.connector.rest.pagination.EndpointConfig;
import io.lumnik.hub.connector.rest.pagination.PaginationFactory;
import io.lumnik.hub.connector.rest.pagination.PaginationStrategy;
import io.lumnik.hub.connector.rest.reliability.RateLimiterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The cursor contract and the incremental modes of RestEndpointReader, at unit speed.
 *
 * <p>{@link RestIncrementalIT} already drives the happy paths end-to-end through the engine with
 * WireMock — a full re-run and a watermark delta. What it does not reach are the EDGES: the
 * one-fetch-per-run short circuit, a missing endpoint row, the deferred {@code api_cursor} mode, a
 * watermark mode with no path to read, and an empty page that must not rewind the watermark. Those
 * are here, and they run in milliseconds instead of container-seconds.
 *
 * <p>The pagination strategy is the seam that makes this possible without HTTP or a database: the
 * reader asks it for the first request, the items, and the next request, so a scripted fake
 * exercises the whole loop. The static kind detectors are already pinned in
 * {@link RestEndpointReaderTest}; they are not repeated here.
 */
class RestEndpointReaderCursorTest {

    private static final UUID RUN = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID ENDPOINT = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final UUID CONNECTOR = UUID.fromString("cccccccc-0000-0000-0000-000000000003");
    private static final String OTHER_RUN = "99999999-0000-0000-0000-000000000000";

    private final ObjectMapper mapper = new ObjectMapper();

    // ── fakes ──────────────────────────────────────────────────────────────────────────────────

    static final class PassThroughAuth extends AuthProviderRegistry {
        @Override public AuthProvider build(AuthSpec spec) { return r -> r; }
    }

    /** Serves a scripted list of pages; {@code next} walks to the end then stops. */
    static final class ScriptedPagination extends PaginationFactory {
        List<List<Map<String, Object>>> pages = List.of(List.of());
        @Override public PaginationStrategy build(Map<String, Object> cfg) {
            return new PaginationStrategy() {
                int page = 0;
                @Override public RestRequest first(EndpointConfig c, Object incremental) {
                    return RestRequest.get("https://erp.test/orders");
                }
                @Override public List<Map<String, Object>> items(RestResponse resp) {
                    return pages.get(Math.min(page, pages.size() - 1));
                }
                @Override public RestRequest next(RestResponse resp, RestRequest current) {
                    return ++page < pages.size() ? current : null;
                }
            };
        }
    }

    static final class RecordingHttp extends RestHttpClient {
        final List<RestRequest> calls = new ArrayList<>();
        @Override public RestResponse execute(RestRequest req) {
            calls.add(req);
            return new RestResponse(200, Map.of(), "{}", null);
        }
    }

    /** `followRedirects(NEVER)` means a 3xx now flows straight to the reader — this is what one
     * looks like on the wire: a redirect body (typically HTML), not JSON. */
    static final class RedirectingHttp extends RestHttpClient {
        @Override public RestResponse execute(RestRequest req) {
            return RestResponse.of(302, Map.of("Location", List.of("https://erp.test/login")),
                "<html>Found. Redirecting to <a href=\"/login\">/login</a></html>");
        }
    }

    /** The endpoint the engine resolves before the read; null models a row that is not there. */
    private ConnectorEndpoint endpoint = new ConnectorEndpoint();
    private final ScriptedPagination pagination = new ScriptedPagination();
    private final RecordingHttp http = new RecordingHttp();

    private RestEndpointReader reader() {
        return new RestEndpointReader(endpoint, new PassThroughAuth(), pagination,
            new RateLimiterRegistry(), http, mapper);
    }

    private ConnectorContext ctx(Map<String, Object> config) {
        return new ConnectorContext(RUN, CONNECTOR, ENDPOINT, 1L, "erp", "rest-generic",
            config, new SyncMode.FullRefresh(), null, null, null, null, null, new OwnedRunScope("test"));
    }

    private ConnectorCursor cursorOf(String watermark, String emittedByRun) {
        ObjectNode n = mapper.createObjectNode();
        if (watermark == null) n.putNull("watermark"); else n.put("watermark", watermark);
        if (emittedByRun == null) n.putNull("emittedByRun"); else n.put("emittedByRun", emittedByRun);
        return new ConnectorCursor(n, Instant.now());
    }

    private static Map<String, Object> item(String updatedAt) {
        return Map.of("id", "x" + updatedAt, "updated_at", updatedAt);
    }

    private static Map<String, Object> watermarkConfig(String... extra) {
        Map<String, Object> inc = new HashMap<>(Map.of("mode", "watermark", "param", "updated_since"));
        for (int i = 0; i < extra.length; i += 2) inc.put(extra[i], extra[i + 1]);
        return Map.of("base_url", "https://erp.test", "incremental", inc);
    }

    @SuppressWarnings("unchecked")
    private static ReadResult.HasMore<Map<String, Object>> hasMore(ReadResult<Map<String, Object>> r) {
        return (ReadResult.HasMore<Map<String, Object>>) assertInstanceOf(ReadResult.HasMore.class, r);
    }

    // ── one fetch per run ──────────────────────────────────────────────────────────────────────

    @Test
    void a_cursor_stamped_with_this_run_short_circuits_without_touching_the_network() {
        ReadResult<Map<String, Object>> r = reader()
            .read(100, cursorOf(null, RUN.toString()), ctx(Map.of("base_url", "https://erp.test")));

        assertInstanceOf(ReadResult.EndOfStream.class, r, "this run's single fetch already happened");
        assertTrue(http.calls.isEmpty(), "and no request is needed to discover that");
    }

    @Test
    void a_cursor_from_a_previous_run_fetches_again_and_claims_this_run() {
        pagination.pages = List.of(List.of(item("2026-01-01")));

        var more = hasMore(reader().read(100, cursorOf("2025-12-31", OTHER_RUN),
            ctx(Map.of("base_url", "https://erp.test"))));

        assertEquals(1, more.records().size());
        assertEquals(RUN.toString(), more.nextCursor().value().get("emittedByRun").asText(),
            "the new cursor claims this run, so the engine's next chunk short-circuits");
    }

    @Test
    void an_absent_cursor_is_a_first_run_and_fetches() {
        pagination.pages = List.of(List.of(item("2026-01-01")));

        hasMore(reader().read(100, null, ctx(Map.of("base_url", "https://erp.test"))));

        assertEquals(1, http.calls.size());
    }

    @Test
    void a_missing_endpoint_row_fails_loudly_rather_than_reading_nothing() {
        endpoint = null;
        RestEndpointReader reader = reader();

        var e = assertThrows(IllegalStateException.class,
            () -> reader.read(100, null, ctx(Map.of("base_url", "https://erp.test"))));
        assertTrue(e.getMessage().contains(ENDPOINT.toString()),
            "the message names the endpoint it could not find: " + e.getMessage());
    }

    @Test
    void every_page_is_followed_and_the_items_arrive_as_one_batch() {
        pagination.pages = List.of(
            List.of(item("2026-01-01"), item("2026-01-02")),
            List.of(item("2026-01-03")));

        var more = hasMore(reader().read(100, null, ctx(Map.of("base_url", "https://erp.test"))));

        assertEquals(2, http.calls.size(), "two pages, two requests");
        assertEquals(3, more.records().size(), "one endpoint = one chunk: every page lands in a single batch");
    }

    /**
     * {@code RestHttpClient} now uses {@code followRedirects(NEVER)}, so a 3xx from a redirecting or
     * hijacked upstream flows straight through to the reader. Before this test, nothing inspected
     * {@code resp.status()}: the redirect's (non-JSON) body failed {@code readTree} and became an
     * empty {@code ObjectNode}, so the run completed GREEN with zero records — the conservation
     * invariant ({@code records_in = records_out + records_skipped}) is satisfied by a confidently
     * wrong empty result. That is the cardinal failure mode this project refuses to ship silently.
     */
    @Test
    void a_redirect_is_refused_loudly_instead_of_completing_as_a_silent_empty_run() {
        pagination.pages = List.of(List.of(item("2026-01-01")));
        RestEndpointReader reader = new RestEndpointReader(endpoint, new PassThroughAuth(),
            pagination, new RateLimiterRegistry(), new RedirectingHttp(), mapper);

        var e = assertThrows(ConnectorException.class,
            () -> reader.read(100, null, ctx(Map.of("base_url", "https://erp.test"))));

        assertTrue(e.getMessage().contains("302"), "names the status: " + e.getMessage());
        assertTrue(e.getMessage().contains("https://erp.test/login"), "names the Location: " + e.getMessage());
    }

    // ── incremental ────────────────────────────────────────────────────────────────────────────

    @Test
    void full_mode_sends_no_param_and_discards_any_prior_watermark() {
        pagination.pages = List.of(List.of(item("2026-01-01")));

        var more = hasMore(reader().read(100, cursorOf("2025-01-01", null),
            ctx(Map.of("base_url", "https://erp.test"))));

        assertTrue(http.calls.get(0).queryParams().isEmpty(), "no incremental block → nothing appended");
        assertTrue(more.nextCursor().value().get("watermark").isNull(),
            "full mode stores a null watermark — the prior value is not carried forward");
    }

    @Test
    void watermark_mode_sends_the_stored_value_as_the_configured_param() {
        pagination.pages = List.of(List.of(item("2026-01-05")));

        reader().read(100, cursorOf("2026-01-01", null),
            ctx(watermarkConfig("watermark_path", "$.updated_at")));

        assertEquals("2026-01-01", http.calls.get(0).queryParams().get("updated_since"));
    }

    @Test
    void watermark_mode_on_a_first_run_sends_nothing_because_there_is_nothing_to_send() {
        pagination.pages = List.of(List.of(item("2026-01-05")));

        reader().read(100, null, ctx(watermarkConfig("watermark_path", "$.updated_at")));

        assertTrue(http.calls.get(0).queryParams().isEmpty(), "no stored watermark → no param");
    }

    @Test
    void the_new_watermark_is_the_string_max_across_every_item_of_every_page() {
        pagination.pages = List.of(
            List.of(item("2026-01-05"), item("2026-01-09")),
            List.of(item("2026-01-07")));

        var more = hasMore(reader().read(100, cursorOf("2026-01-01", null),
            ctx(watermarkConfig("watermark_path", "$.updated_at"))));

        assertEquals("2026-01-09", more.nextCursor().value().get("watermark").asText(),
            "MAX by STRING compare — ISO timestamps and monotonic ids only, as the javadoc warns");
    }

    @Test
    void an_empty_result_keeps_the_prior_watermark_rather_than_rewinding_it() {
        pagination.pages = List.of(List.of());

        var more = hasMore(reader().read(100, cursorOf("2026-01-01", null),
            ctx(watermarkConfig("watermark_path", "$.updated_at"))));

        assertEquals("2026-01-01", more.nextCursor().value().get("watermark").asText(),
            "no items must never rewind the watermark — that would re-ingest the world");
    }

    @Test
    void watermark_mode_without_a_path_can_never_advance_and_keeps_the_prior_value() {
        pagination.pages = List.of(List.of(item("2026-01-09")));

        var more = hasMore(reader().read(100, cursorOf("2026-01-01", null), ctx(watermarkConfig())));

        assertEquals("2026-01-01", more.nextCursor().value().get("watermark").asText(),
            "validate refuses this shape (C4); if one reaches the runtime it simply never advances");
    }

    /**
     * The class javadoc documents {@code api_cursor} as deferred, and the validator warns on it.
     * This pins what the RUNTIME does if one arrives anyway: treats it as full.
     */
    @Test
    void api_cursor_mode_is_treated_as_full_because_it_is_deferred() {
        pagination.pages = List.of(List.of(item("2026-01-05")));
        Map<String, Object> cfg = Map.of("base_url", "https://erp.test",
            "incremental", Map.of("mode", "api_cursor", "param", "updated_since",
                                  "watermark_path", "$.updated_at"));

        var more = hasMore(reader().read(100, cursorOf("2026-01-01", null), ctx(cfg)));

        assertTrue(http.calls.get(0).queryParams().isEmpty(), "api_cursor sends no incremental param");
        assertTrue(more.nextCursor().value().get("watermark").isNull(),
            "and stores no watermark — exactly the full-mode behaviour");
    }

    /**
     * A mis-typed block used to blow up with a ClassCastException — loud, if ugly. It must not
     * become a silent fall-back to defaults: `pagination: "foo"` would then quietly page not at all,
     * and RestConnectorValidator's refusal of a scalar `incremental` would lose the runtime backstop
     * it explicitly counts on for configs written straight to the DB, bypassing apply.
     */
    @Test
    void a_config_block_that_is_a_scalar_fails_loudly_instead_of_defaulting() {
        Map<String, Object> cfg = Map.of("base_url", "https://erp.test", "pagination", "not-a-block");

        var e = assertThrows(RuntimeException.class, () -> reader().read(100, null, ctx(cfg)));
        assertTrue(e.getMessage() != null && e.getMessage().contains("pagination"),
            "the failure must name the offending block: " + e.getMessage());
        assertTrue(http.calls.isEmpty(), "and nothing is fetched under a misread configuration");
    }

    @Test
    void an_absent_block_still_defaults_quietly_because_absence_is_legitimate() {
        pagination.pages = List.of(List.of(item("2026-01-01")));

        // no "pagination", no "auth", no "incremental" — all optional
        hasMore(reader().read(100, null, ctx(Map.of("base_url", "https://erp.test"))));

        assertEquals(1, http.calls.size(), "an absent optional block is not a misconfiguration");
    }

    @Test
    void an_item_missing_the_watermark_path_is_skipped_rather_than_poisoning_the_max() {
        pagination.pages = List.of(List.of(item("2026-01-05"), Map.of("id", "no-date")));

        var more = hasMore(reader().read(100, cursorOf("2026-01-01", null),
            ctx(watermarkConfig("watermark_path", "$.updated_at"))));

        assertEquals("2026-01-05", more.nextCursor().value().get("watermark").asText(),
            "a null extraction is ignored, not treated as a value");
    }
}
