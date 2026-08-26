package io.lumnik.hub.connector.rest.runtime;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Page-granular chunking of RestEndpointReader, for both full and watermark modes.
 *
 * <p>{@link RestEndpointReaderCursorTest} pins the cursor contract and the incremental modes; this
 * class pins the CHUNKING behaviour that replaced the one-endpoint-one-chunk trade-off: a read
 * stops at the page that crosses {@code chunkSize}, the resume state round-trips through the
 * persisted cursor JSON, and the concatenation of all chunks conserves every item exactly once.
 * Watermark mode shares this same chunk loop under the staged-promotion cursor design (a
 * {@code watermarkCandidate} field grows per chunk; only the exhausted chunk promotes it into
 * {@code watermark}) — see the "staged-promotion" section near the bottom of this class, which
 * deliberately REPLACES the earlier single-fetch pin this class used to carry.
 *
 * <p>The fakes mirror the production strategies faithfully where it matters: the page fake keeps
 * NO state inside the strategy object — the current page lives entirely in the request's query
 * string, exactly like {@code PagePaginationStrategy} — because a fresh reader (and so a fresh
 * strategy) is built per chunk, and only what the request carries can survive into the cursor.
 */
class RestEndpointReaderChunkingTest {

    private static final UUID RUN = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID ENDPOINT = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final UUID CONNECTOR = UUID.fromString("cccccccc-0000-0000-0000-000000000003");
    private static final String OTHER_RUN = "99999999-0000-0000-0000-000000000000";

    private final ObjectMapper mapper = new ObjectMapper();

    // ── fakes ──────────────────────────────────────────────────────────────────────────────────

    static final class PassThroughAuth extends AuthProviderRegistry {
        @Override public AuthProvider build(AuthSpec spec) { return r -> r; }
    }

    /** Serves the page named by the request's {@code p} query param — state on the wire, not in the fake. */
    static final class PagedHttp extends RestHttpClient {
        final List<RestRequest> calls = new ArrayList<>();
        @Override public RestResponse execute(RestRequest req) {
            calls.add(req);
            int p = Integer.parseInt(req.queryParams().getOrDefault("p", "0"));
            return RestResponse.of(200, Map.of(), "{\"page\": " + p + "}");
        }
    }

    /**
     * A stateless page strategy, like the production ones: the current page is read from the
     * RESPONSE (which {@link PagedHttp} stamped from the request), and {@code next} advances the
     * request's query param. Nothing survives in the strategy object between calls.
     */
    static final class StatelessPagePagination extends PaginationFactory {
        List<List<Map<String, Object>>> pages = List.of(List.of());
        @Override public PaginationStrategy build(Map<String, Object> cfg) {
            return new PaginationStrategy() {
                @Override public RestRequest first(EndpointConfig c, Object incremental) {
                    return RestRequest.get("https://erp.test/orders").withQuery("p", "0");
                }
                @Override public List<Map<String, Object>> items(RestResponse resp) {
                    int p = resp.json().get("page").asInt();
                    return p < pages.size() ? pages.get(p) : List.of();
                }
                @Override public RestRequest next(RestResponse resp, RestRequest current) {
                    int p = resp.json().get("page").asInt();
                    return p + 1 < pages.size() ? current.withQuery("p", String.valueOf(p + 1)) : null;
                }
            };
        }
    }

    private final ConnectorEndpoint endpoint = new ConnectorEndpoint();
    private final StatelessPagePagination pagination = new StatelessPagePagination();
    private final PagedHttp http = new PagedHttp();

    /** Fresh reader per read, exactly like the engine builds one per chunk. */
    private RestEndpointReader reader() {
        return new RestEndpointReader(endpoint, new PassThroughAuth(), pagination,
            new RateLimiterRegistry(), http, mapper);
    }

    private ConnectorContext ctx(UUID runId, Map<String, Object> config) {
        return new ConnectorContext(runId, CONNECTOR, ENDPOINT, 1L, "erp", "rest-generic",
            config, new SyncMode.FullRefresh(), null, null, null, null);
    }

    private ConnectorContext ctx(Map<String, Object> config) {
        return ctx(RUN, config);
    }

    private ConnectorCursor cursorOf(String watermark, String emittedByRun) {
        ObjectNode n = mapper.createObjectNode();
        if (watermark == null) n.putNull("watermark"); else n.put("watermark", watermark);
        if (emittedByRun == null) n.putNull("emittedByRun"); else n.put("emittedByRun", emittedByRun);
        return new ConnectorCursor(n, Instant.now());
    }

    /** 5 pages x 4 items = 20 items, ids 0..19 in order. */
    private void twentyItemsInFivePages() {
        List<List<Map<String, Object>>> pages = new ArrayList<>();
        for (int p = 0; p < 5; p++) {
            List<Map<String, Object>> page = new ArrayList<>();
            for (int i = 0; i < 4; i++) page.add(Map.of("id", p * 4 + i));
            pages.add(page);
        }
        pagination.pages = pages;
    }

    /** Same shape as {@link #twentyItemsInFivePages}, plus a monotonic {@code updated_at} per item. */
    private void twentyItemsWithUpdatedAtInFivePages() {
        List<List<Map<String, Object>>> pages = new ArrayList<>();
        for (int p = 0; p < 5; p++) {
            List<Map<String, Object>> page = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                int id = p * 4 + i;
                page.add(Map.of("id", id, "updated_at", "2026-01-" + String.format("%02d", id + 1)));
            }
            pages.add(page);
        }
        pagination.pages = pages;
    }

    private static Map<String, Object> fullConfig() {
        return Map.of("base_url", "https://erp.test");
    }

    private static Map<String, Object> watermarkConfig() {
        return Map.of("base_url", "https://erp.test",
            "incremental", Map.of("mode", "watermark", "param", "updated_since",
                                  "watermark_path", "$.updated_at"));
    }

    @SuppressWarnings("unchecked")
    private static ReadResult.HasMore<Map<String, Object>> hasMore(ReadResult<Map<String, Object>> r) {
        return (ReadResult.HasMore<Map<String, Object>>) assertInstanceOf(ReadResult.HasMore.class, r);
    }

    // ── full mode chunks at page granularity ──────────────────────────────────────────────────

    @Test
    void full_mode_stops_at_the_page_that_crosses_chunk_size_instead_of_fetching_everything() {
        twentyItemsInFivePages();

        var more = hasMore(reader().read(8, null, ctx(fullConfig())));

        assertTrue(more.records().size() <= 12,
            "chunkSize 8 + one page of overshoot at most — not all 20: got " + more.records().size());
        assertEquals(8, more.records().size(), "pages of 4: the second page reaches 8 exactly");
        assertEquals(2, http.calls.size(), "and only the pages needed for this chunk were fetched");
    }

    @Test
    void the_resume_cursor_round_trips_through_json_and_carries_the_pagination_state() {
        twentyItemsInFivePages();

        var more = hasMore(reader().read(8, null, ctx(fullConfig())));

        JsonNode cur = more.nextCursor().value();
        assertEquals(RUN.toString(), cur.get("emittedByRun").asText());
        JsonNode resume = cur.get("resume");
        assertNotNull(resume, "a mid-pagination cursor carries the resume state");
        assertEquals("GET", resume.get("method").asText());
        assertEquals("https://erp.test/orders", resume.get("url").asText());
        assertEquals("2", resume.get("query").get("p").asText(), "the next page to fetch");
        assertEquals(2, cur.get("pages").asInt(), "pages fetched so far, for the run-wide cap");

        // Round-trip: re-serialize through Jackson as the cursor store does.
        ConnectorCursor reparsed = new ConnectorCursor(
            (ObjectNode) assertDoesNotThrow(() -> mapper.readTree(mapper.writeValueAsString(cur))),
            Instant.now());
        var next = hasMore(reader().read(8, reparsed, ctx(fullConfig())));
        assertEquals(8, next.records().size(), "the resumed read continues from page 2");
        assertEquals(8, next.records().get(0).get("id"), "…which starts at item 8, no repeat, no gap");
    }

    @Test
    void all_chunks_together_conserve_the_twenty_items_exactly_once_and_in_order() {
        twentyItemsInFivePages();

        List<Map<String, Object>> collected = new ArrayList<>();
        ConnectorCursor cursor = null;
        int reads = 0;
        while (true) {
            ReadResult<Map<String, Object>> r = reader().read(8, cursor, ctx(fullConfig()));
            if (r instanceof ReadResult.EndOfStream<Map<String, Object>> eos) {
                collected.addAll(eos.records());
                break;
            }
            var more = hasMore(r);
            collected.addAll(more.records());
            cursor = more.nextCursor();
            assertTrue(++reads < 20, "must terminate");
        }

        assertEquals(20, collected.size(), "records_in conservation: every item exactly once");
        for (int i = 0; i < 20; i++) {
            assertEquals(i, collected.get(i).get("id"), "…and in source order at position " + i);
        }
        assertEquals(5, http.calls.size(), "each page fetched exactly once across the whole run");
    }

    @Test
    void when_pagination_is_exhausted_the_tail_rides_its_has_more_and_the_next_read_ends_the_stream() {
        twentyItemsInFivePages();

        var first = hasMore(reader().read(8, null, ctx(fullConfig())));           // pages 0-1
        var second = hasMore(reader().read(8, first.nextCursor(), ctx(fullConfig()))); // pages 2-3
        var tail = hasMore(reader().read(8, second.nextCursor(), ctx(fullConfig())));  // page 4 (tail)

        assertEquals(4, tail.records().size(), "the last page rides its own HasMore");
        assertTrue(tail.nextCursor().value().path("resume").isMissingNode()
                || tail.nextCursor().value().get("resume").isNull(),
            "an exhausted run carries no resume state");

        ReadResult<Map<String, Object>> done = reader().read(8, tail.nextCursor(), ctx(fullConfig()));
        assertInstanceOf(ReadResult.EndOfStream.class, done);
    }

    @Test
    void a_resume_cursor_from_a_previous_run_is_ignored_and_the_new_run_re_reads_from_the_start() {
        twentyItemsInFivePages();
        // A run that crashed mid-pagination leaves a resume cursor behind; FULL semantics re-read.
        ObjectNode stale = mapper.createObjectNode();
        stale.putNull("watermark");
        stale.put("emittedByRun", OTHER_RUN);
        ObjectNode resume = stale.putObject("resume");
        resume.put("method", "GET");
        resume.put("url", "https://erp.test/orders");
        resume.putObject("query").put("p", "3");
        stale.put("pages", 3);

        var more = hasMore(reader().read(8, new ConnectorCursor(stale, Instant.now()), ctx(fullConfig())));

        assertEquals(0, more.records().get(0).get("id"),
            "a new run starts from page 0 — FULL re-runs re-read everything, idempotent via upsert");
    }

    @Test
    void an_unusable_resume_state_fails_loudly_naming_the_endpoint_instead_of_restarting_at_page_one() {
        twentyItemsInFivePages();
        ObjectNode broken = mapper.createObjectNode();
        broken.putNull("watermark");
        broken.put("emittedByRun", RUN.toString());
        broken.putObject("resume").put("garbage", true);   // no method, no url

        RestEndpointReader reader = reader();
        ConnectorCursor cursor = new ConnectorCursor(broken, Instant.now());
        ConnectorContext context = ctx(fullConfig());
        var e = assertThrows(ConnectorException.class, () -> reader.read(8, cursor, context));

        assertTrue(e.getMessage().contains(ENDPOINT.toString()),
            "names the endpoint: " + e.getMessage());
        assertTrue(e.getMessage().contains("re-run the connector"),
            "tells the operator the safe recovery: " + e.getMessage());
        assertTrue(http.calls.isEmpty(), "and NEVER silently restarts from page 1");
    }

    @Test
    void max_pages_caps_the_total_across_the_run_not_per_read() {
        twentyItemsInFivePages();
        // A cursor claiming 9_999 pages already fetched: this read may fetch exactly one more.
        ObjectNode nearCap = mapper.createObjectNode();
        nearCap.putNull("watermark");
        nearCap.put("emittedByRun", RUN.toString());
        ObjectNode resume = nearCap.putObject("resume");
        resume.put("method", "GET");
        resume.put("url", "https://erp.test/orders");
        resume.putObject("query").put("p", "1");
        nearCap.put("pages", 9_999);

        var more = hasMore(reader().read(100, new ConnectorCursor(nearCap, Instant.now()), ctx(fullConfig())));

        assertEquals(1, http.calls.size(), "page 10_000 is the last one the cap allows");
        assertEquals(4, more.records().size());
        assertTrue(more.nextCursor().value().path("resume").isMissingNode()
                || more.nextCursor().value().get("resume").isNull(),
            "at the cap the run finalizes, mirroring the old loop's stop-at-MAX_PAGES");
    }

    // ── strategies that cannot resume stay single-fetch ────────────────────────────────────────

    @Test
    void soap_pagination_stays_on_the_single_fetch_path_because_its_state_lives_in_the_strategy() {
        twentyItemsInFivePages();
        Map<String, Object> cfg = Map.of("base_url", "https://erp.test",
            "pagination", Map.of("kind", "soap"));   // routing is by declared kind, fake strategy

        var more = hasMore(reader().read(8, null, ctx(cfg)));

        assertEquals(20, more.records().size(),
            "SOAP keeps one-endpoint-one-chunk: offset and render context live inside the strategy");
        ReadResult<Map<String, Object>> done = reader().read(8, more.nextCursor(), ctx(cfg));
        assertInstanceOf(ReadResult.EndOfStream.class, done);
    }

    @Test
    void soap_in_watermark_mode_still_commits_the_watermark_over_the_single_fetch() {
        twentyItemsWithUpdatedAtInFivePages();
        // SOAP + watermark: SOAP cannot chunk (single fetch), but watermark mode must still advance
        // the cursor's watermark over the fetched items — exactly as the pre-PR-B single-fetch path
        // did. Dropping it (cursor(null,...)) makes every run re-fetch the same window forever.
        Map<String, Object> cfg = new java.util.HashMap<>(watermarkConfig());
        cfg.put("pagination", Map.of("kind", "soap"));

        var more = hasMore(reader().read(8, null, ctx(cfg)));

        assertEquals(20, more.records().size(), "SOAP still single-fetches all pages");
        JsonNode wm = more.nextCursor().value().path("watermark");
        assertFalse(wm.isMissingNode() || wm.isNull(),
            "SOAP+watermark must commit a watermark, not drop it to null");
        assertEquals("2026-01-20", wm.asText(),
            "the committed watermark is the max updated_at over the fetched items");
    }

    @Test
    void soap_watermark_mode_with_no_path_preserves_the_prior_watermark_never_nulls_it() {
        twentyItemsWithUpdatedAtInFivePages();
        // watermark mode ON but watermark_path missing (misconfig): maxWatermark can't extract, so
        // the prior watermark must be CARRIED THROUGH — exactly as the chunking branch does with a
        // null path. Nulling it (a too-eager path!=null guard) reintroduces the re-fetch-forever bug.
        Map<String, Object> cfg = new java.util.HashMap<>();
        cfg.put("base_url", "https://erp.test");
        cfg.put("pagination", Map.of("kind", "soap"));
        cfg.put("incremental", Map.of("mode", "watermark", "param", "updated_since")); // no watermark_path
        ConnectorCursor prior = cursorOf("2026-01-05", null);

        var more = hasMore(reader().read(8, prior, ctx(cfg)));

        JsonNode wm = more.nextCursor().value().path("watermark");
        assertFalse(wm.isMissingNode() || wm.isNull(),
            "a path-less watermark run must carry the prior watermark, not drop it to null");
        assertEquals("2026-01-05", wm.asText(), "the prior watermark is preserved unchanged");
    }

    // ── watermark mode: staged-promotion chunking (PR B — replaces the single-fetch pin above) ──
    //
    // PR A's pin here (`watermark_mode_still_single_fetches_and_commits_the_watermark_once_over_
    // all_items`) asserted watermark mode never chunks, because a watermark persisted mid-
    // pagination could make the NEXT run start past pages not yet fetched — silent data loss. That
    // pin is deliberately REPLACED (not deleted) by the three tests below, which pin the design
    // that unpins it: `watermark` never moves mid-run; a separate `watermarkCandidate` field
    // carries the running max and is only promoted into `watermark` once pagination is fully
    // exhausted. See the class javadoc's "Staged promotion under chunking" section.

    @Test
    void watermark_mode_chunks_the_page_window_keeping_the_old_watermark_until_the_final_chunk_promotes_it() {
        twentyItemsWithUpdatedAtInFivePages();
        Map<String, Object> cfg = watermarkConfig();

        var chunk1 = hasMore(reader().read(8, cursorOf("2025-12-31", null), ctx(cfg)));
        assertEquals(8, chunk1.records().size(), "pages of 4: the second page reaches 8 exactly");
        JsonNode c1 = chunk1.nextCursor().value();
        assertEquals("2025-12-31", c1.get("watermark").asText(),
            "the OLD, pre-run watermark — untouched mid-run");
        assertEquals("2026-01-08", c1.get("watermarkCandidate").asText(),
            "the candidate = max over items committed so far (chunk 1 only)");
        assertNotNull(c1.get("resume"), "mid-pagination cursor still carries the resume state");

        var chunk2 = hasMore(reader().read(8, chunk1.nextCursor(), ctx(cfg)));
        assertEquals(8, chunk2.records().size());
        JsonNode c2 = chunk2.nextCursor().value();
        assertEquals("2025-12-31", c2.get("watermark").asText(),
            "still OLD after two chunks — only the FINAL chunk promotes");
        assertEquals("2026-01-16", c2.get("watermarkCandidate").asText(),
            "candidate grew with chunk 2's items on top of chunk 1's");

        var chunk3 = hasMore(reader().read(8, chunk2.nextCursor(), ctx(cfg)));
        assertEquals(4, chunk3.records().size(), "the tail page rides its own HasMore");
        JsonNode c3 = chunk3.nextCursor().value();
        assertEquals("2026-01-20", c3.get("watermark").asText(),
            "pagination exhausted on this chunk: candidate promoted into watermark");
        assertTrue(c3.path("watermarkCandidate").isMissingNode(),
            "the promoted cursor is the legacy two-field shape — no leftover candidate");
        assertTrue(c3.path("resume").isMissingNode(), "…and no resume either");

        assertEquals(5, http.calls.size(), "each page fetched exactly once across the whole run");
        assertEquals("2025-12-31", http.calls.get(0).queryParams().get("updated_since"),
            "only the first page of the run sends the incremental filter, using the OLD watermark");

        ReadResult<Map<String, Object>> done = reader().read(8, chunk3.nextCursor(), ctx(cfg));
        assertInstanceOf(ReadResult.EndOfStream.class, done);
    }

    /**
     * THE SKIP-IMPOSSIBILITY TEST. A run dies after committing chunk 1 (its cursor is left behind
     * with the OLD watermark, a grown candidate, and a live resume state). The engine starts a NEW
     * run — a fresh runId — with that leftover cursor. Because the leftover {@code resume} was
     * stamped by a DIFFERENT run, {@code resuming} is false: the new run starts from page 0 again,
     * filtering by the OLD watermark (never the candidate the dead run reached). Nothing the dead
     * run committed or missed becomes unreachable — at worst, page 0's items are read twice, which
     * the {@code _row_hash} / {@code ON CONFLICT} upsert absorbs.
     */
    @Test
    void a_dead_run_never_promotes_the_watermark_so_the_next_run_re_reads_from_the_old_point() {
        twentyItemsWithUpdatedAtInFivePages();
        Map<String, Object> cfg = watermarkConfig();

        var chunk1 = hasMore(reader().read(8, cursorOf("2025-12-31", null), ctx(cfg)));
        JsonNode dead = chunk1.nextCursor().value();
        assertEquals("2025-12-31", dead.get("watermark").asText(), "OLD watermark, unpromoted");
        assertNotNull(dead.get("watermarkCandidate"), "the dead run did make progress on the candidate");
        assertNotNull(dead.get("resume"), "…and left a live resume state behind");

        UUID newRun = UUID.fromString("dddddddd-0000-0000-0000-000000000004");
        http.calls.clear();
        var restarted = hasMore(reader().read(8, chunk1.nextCursor(), ctx(newRun, cfg)));

        assertEquals("2025-12-31", http.calls.get(0).queryParams().get("updated_since"),
            "the new run filters from the OLD watermark, not the candidate the dead run reached");
        assertEquals(0, restarted.records().get(0).get("id"),
            "and re-reads from page 0 — everything the dead run committed (and everything after) "
                + "is reachable again; nothing is skipped");
    }

    /**
     * Equivalence with the pre-PR-B behaviour: chunked to exhaustion, the FINAL promoted watermark
     * must equal what the old single-fetch {@code maxWatermark(all items, prior)} computed over the
     * very same fixture (the value PR A's pin asserted: {@code "2026-01-20"}).
     */
    @Test
    void the_final_promoted_watermark_matches_the_old_single_fetch_result_and_conserves_every_item() {
        twentyItemsWithUpdatedAtInFivePages();
        Map<String, Object> cfg = watermarkConfig();

        List<Map<String, Object>> collected = new ArrayList<>();
        ConnectorCursor cursor = null;
        String finalWatermark = null;
        int reads = 0;
        while (true) {
            var more = hasMore(reader().read(8, cursor, ctx(cfg)));
            collected.addAll(more.records());
            cursor = more.nextCursor();
            boolean exhausted = cursor.value().path("resume").isMissingNode()
                || cursor.value().get("resume").isNull();
            if (exhausted) {
                finalWatermark = cursor.value().get("watermark").asText();
                break;
            }
            assertTrue(++reads < 20, "must terminate");
        }

        assertEquals(20, collected.size(), "records_in conservation across all chunks");
        assertEquals("2026-01-20", finalWatermark,
            "the promoted watermark equals the old single-fetch maxWatermark over the same items");

        ReadResult<Map<String, Object>> done = reader().read(8, cursor, ctx(cfg));
        assertInstanceOf(ReadResult.EndOfStream.class, done);
    }
}
