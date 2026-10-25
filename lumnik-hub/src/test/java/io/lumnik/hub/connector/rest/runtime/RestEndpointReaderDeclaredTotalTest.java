package io.lumnik.hub.connector.rest.runtime;

import io.lumnik.hub.connector.runtime.OwnedRunScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.hub.connector.api.types.SyncMode;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.rest.RestHttpClient;
import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;
import io.lumnik.hub.connector.rest.pagination.PaginationFactory;
import io.lumnik.hub.connector.rest.reliability.RateLimiterRegistry;
import io.lumnik.hub.connector.secret.SecretRefs;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The source's own declared total, made a GUARD instead of a mere stop condition.
 *
 * <p>{@code total_path} was read only inside {@link
 * io.lumnik.hub.connector.rest.pagination.OffsetPaginationStrategy#next} — to decide when to stop.
 * What the source DECLARES was never confronted with what the run RETURNED, so a run that lost a
 * window of rows finished {@code Completed} with counters that agreed with themselves. The
 * conservation invariant cannot catch that: {@code 18 409 = 18 409 + 0} is self-consistent about a
 * number that is already wrong. Only a witness OUTSIDE the run can tell — and the source hands one
 * over on every page.
 *
 * <p>These tests drive the REAL {@code OffsetPaginationStrategy} through the real
 * {@link PaginationFactory}: a fake strategy could not prove that the value the production strategy
 * already reads is the value the guard compares against.
 */
class RestEndpointReaderDeclaredTotalTest {

    private static final UUID RUN = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000000a");
    private static final UUID ENDPOINT = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000b");
    private static final UUID CONNECTOR = UUID.fromString("cccccccc-0000-0000-0000-00000000000c");

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * An offset/limit source that declares its total on every page — like the `rated` and `offset`
     * dialects of the REST bench. {@code shortPageAtOffset} makes it hand back fewer rows than are
     * due at ONE offset: the shape a hiccuping source produces, and the one that ends pagination
     * early while the run still reports success.
     */
    static final class OffsetHttp extends RestHttpClient {
        private int served;
        /** How many rows the source actually holds. */
        int rows = 20;
        /** What it SAYS it holds — normally {@code rows}, deliberately not always. */
        int declaredTotal = 20;
        /** What it says from the second page on, when a live source's count moves mid-read. */
        Integer declaredTotalFromSecondPage = null;
        /** An offset where the source hands back fewer rows than are due. */
        int shortPageAtOffset = -1;

        @Override public RestResponse execute(RestRequest req) {
            served++;
            int off = Integer.parseInt(req.queryParams().getOrDefault("offset", "0"));
            int limit = Integer.parseInt(req.queryParams().getOrDefault("limit", "4"));
            int due = Math.max(0, Math.min(limit, rows - off));
            int n = (off == shortPageAtOffset) ? 2 : due;
            String body = IntStream.range(0, n)
                .mapToObj(i -> "{\"id\":" + (off + i) + ",\"updated_at\":\"2026-01-01\"}")
                .collect(Collectors.joining(","));
            int declared = (served > 1 && declaredTotalFromSecondPage != null)
                ? declaredTotalFromSecondPage : declaredTotal;
            return RestResponse.of(200, Map.of(),
                "{\"data\":[" + body + "],\"total\":" + declared + "}");
        }
    }

    private final ConnectorEndpoint endpoint = new ConnectorEndpoint();
    private final OffsetHttp http = new OffsetHttp();

    {
        endpoint.setName("partners");
        endpoint.setMethod("GET");
        endpoint.setPath("/partners");
    }

    private RestEndpointReader reader() {
        return new RestEndpointReader(endpoint, new RestEndpointReaderChunkingTest.PassThroughAuth(),
            new PaginationFactory(), new RateLimiterRegistry(), http, mapper, SecretRefs.environmentOnly(name -> null));
    }

    private ConnectorContext ctx(Map<String, Object> config) {
        return new ConnectorContext(RUN, CONNECTOR, ENDPOINT, 1L, "erp", "rest-generic",
            config, new SyncMode.FullRefresh(), null, null, null, null, new OwnedRunScope("test"));
    }

    private static Map<String, Object> offsetConfig() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("base_url", "https://erp.test");
        cfg.put("pagination", Map.of(
            "kind", "offset", "offset_param", "offset", "limit_param", "limit",
            "limit", 4, "total_path", "$.total", "response_path", "$.data"));
        return cfg;
    }

    private static Map<String, Object> watermarkConfig() {
        Map<String, Object> cfg = offsetConfig();
        cfg.put("incremental", Map.of("mode", "watermark", "param", "updated_since",
            "watermark_path", "$.updated_at"));
        return cfg;
    }

    /** Round-trips the cursor through JSON, exactly as {@code connector_cursor} (jsonb) does. */
    private ConnectorCursor persisted(ConnectorCursor c) {
        try {
            return new ConnectorCursor(mapper.readTree(mapper.writeValueAsString(c.value())), Instant.now());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Drives chunk after chunk the way the engine does, feeding each cursor back in. */
    private List<Map<String, Object>> drain(int chunkSize, Map<String, Object> config) {
        List<Map<String, Object>> all = new ArrayList<>();
        ConnectorCursor cursor = null;
        for (int guard = 0; guard < 50; guard++) {
            ReadResult<Map<String, Object>> r = reader().read(chunkSize, cursor, ctx(config));
            if (r instanceof ReadResult.EndOfStream<Map<String, Object>> eos) {
                all.addAll(eos.records());
                return all;
            }
            @SuppressWarnings("unchecked")
            var more = (ReadResult.HasMore<Map<String, Object>>) r;
            all.addAll(more.records());
            cursor = persisted(more.nextCursor());
        }
        throw new IllegalStateException("chunk loop did not terminate");
    }

    @Test
    void a_run_that_returns_fewer_rows_than_the_source_declared_is_refused() {
        http.rows = 20;
        http.declaredTotal = 20;
        http.shortPageAtOffset = 8;   // 2 rows where 4 were due: pagination ends at 10 of 20

        ConnectorException e = assertThrows(ConnectorException.class,
            () -> drain(8, offsetConfig()),
            "a run that silently returns 10 of the 20 rows the source declares must not succeed");

        assertTrue(e.getMessage().contains("10") && e.getMessage().contains("20"),
            "the refusal must name BOTH numbers so the operator can see the gap: " + e.getMessage());
        // #1373: the endpoint as the manifest names it, not the hub's internal id
        assertTrue(e.getMessage().startsWith("Endpoint 'partners': ") && !e.getMessage().contains(ENDPOINT.toString()),
            "the refusal must name the endpoint by its manifest id: " + e.getMessage());
    }

    @Test
    void a_healthy_run_returns_every_row_and_is_never_refused() {
        http.rows = 20;
        http.declaredTotal = 20;

        List<Map<String, Object>> all = assertDoesNotThrow(() -> drain(8, offsetConfig()),
            "the guard must be invisible to a run that lost nothing");

        assertEquals(20, all.size());
    }

    @Test
    void a_watermark_run_returns_fewer_rows_than_the_total_BY_DESIGN_and_is_not_refused() {
        http.rows = 20;
        http.declaredTotal = 20;
        http.shortPageAtOffset = 8;   // 10 of 20 returned — a full-mode run would be refused here

        assertDoesNotThrow(() -> drain(8, watermarkConfig()),
            "an incremental run FILTERS: comparing it against the source's unfiltered total "
                + "would fail every incremental run on a source that declares one");
    }

    @Test
    void stopping_at_the_page_cap_short_of_the_declared_total_is_refused_like_any_other_short_run() {
        // The run-wide MAX_PAGES cap ends a read by handing back no next request — the same shape as
        // real exhaustion. Before this guard that finalized the run green over a truncated extract;
        // where the source declared a total, it no longer can. (A source that declares none is still
        // capped silently — pinned as today's behaviour by max_pages_caps_the_total_across_the_run.)
        http.rows = 20;
        http.declaredTotal = 20;

        var nearCap = mapper.createObjectNode();
        nearCap.putNull("watermark");
        nearCap.put("emittedByRun", RUN.toString());
        var resume = nearCap.putObject("resume");
        resume.put("method", "GET");
        resume.put("url", "https://erp.test/partners");
        resume.putObject("query").put("offset", "0").put("limit", "4");
        nearCap.put("pages", 9_999);   // this read may fetch exactly one more page

        ConnectorException e = assertThrows(ConnectorException.class,
            () -> reader().read(100, new ConnectorCursor(nearCap, Instant.now()), ctx(offsetConfig())),
            "a run cut off at the page cap has lost rows the source declared — it must not report success");

        assertTrue(e.getMessage().contains("4") && e.getMessage().contains("20"), e.getMessage());
    }

    @Test
    void a_total_that_moves_during_the_read_is_a_live_source_and_not_a_defect() {
        http.rows = 20;
        http.declaredTotal = 24;                  // what page 1 claims
        http.declaredTotalFromSecondPage = 20;    // rows were deleted while we paged

        List<Map<String, Object>> all = assertDoesNotThrow(() -> drain(8, offsetConfig()),
            "a source that changes its own count mid-read has not lost anything — the run must stand");

        assertEquals(20, all.size(), "and every row the source did serve is still returned");
    }
}
