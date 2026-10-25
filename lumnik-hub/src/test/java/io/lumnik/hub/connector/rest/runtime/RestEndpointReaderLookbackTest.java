package io.lumnik.hub.connector.rest.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.hub.connector.api.types.SyncMode;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.rest.RestHttpClient;
import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;
import io.lumnik.hub.connector.rest.auth.AuthProvider;
import io.lumnik.hub.connector.rest.auth.AuthProviderRegistry;
import io.lumnik.hub.connector.rest.auth.AuthSpec;
import io.lumnik.hub.connector.rest.pagination.PaginationFactory;
import io.lumnik.hub.connector.rest.reliability.RateLimiterRegistry;
import io.lumnik.hub.connector.runtime.OwnedRunScope;
import io.lumnik.hub.connector.secret.SecretRefs;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * #1397: a change made during an incremental run must reach the next one. The source filters inclusively on
 * {@code updated_since} and pages by offset over its own id order, as the order API of the lab does.
 */
class RestEndpointReaderLookbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** An order API: rows by id, filtered on updated_since; {@link #afterPage} runs once a page is served. */
    static final class OrderApi extends RestHttpClient {
        final Map<String, String> updatedAt = new LinkedHashMap<>();
        final List<RestRequest> calls = new ArrayList<>();
        Consumer<Integer> afterPage = page -> {};

        @Override public RestResponse execute(RestRequest req) throws Exception {
            calls.add(req);
            String since = req.queryParams().get("updated_since");
            int offset = Integer.parseInt(req.queryParams().getOrDefault("offset", "0"));
            int limit = Integer.parseInt(req.queryParams().getOrDefault("limit", "2"));
            List<Map<String, Object>> rows = new ArrayList<>();
            updatedAt.forEach((id, at) -> {
                if (since == null || at.compareTo(since) >= 0) {   // one zone, one precision: text order is time order
                    rows.add(Map.of("id", id, "updated_at", at));
                }
            });
            List<Map<String, Object>> page = rows.subList(Math.min(offset, rows.size()), Math.min(offset + limit, rows.size()));
            String body = MAPPER.writeValueAsString(Map.of("data", page));
            afterPage.accept(offset);
            return RestResponse.of(200, Map.of(), body);
        }
    }

    private final OrderApi api = new OrderApi();

    private RestEndpointReader reader() {
        AuthProviderRegistry none = new AuthProviderRegistry() {
            @Override public AuthProvider build(AuthSpec spec) { return r -> r; }
        };
        ConnectorEndpoint ep = new ConnectorEndpoint();
        ep.setName("orders");
        ep.setMethod("GET");
        ep.setPath("/api/orders");
        return new RestEndpointReader(ep, none, new PaginationFactory(), new RateLimiterRegistry(), api, MAPPER,
            SecretRefs.environmentOnly(name -> null));
    }

    private static Map<String, Object> config(String lookback) {
        Map<String, Object> inc = new HashMap<>(Map.of("mode", "watermark", "param", "updated_since",
            "watermark_path", "$.updated_at"));
        if (lookback != null) inc.put("lookback", lookback);
        return Map.of("base_url", "https://orders.test", "incremental", inc,
            "pagination", Map.of("kind", "offset", "offset_param", "offset", "limit_param", "limit", "limit", 2,
                "response_path", "$.data"));
    }

    private static ConnectorContext ctx(Map<String, Object> config) {
        return new ConnectorContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1L, "erp", "rest-generic",
            config, new SyncMode.FullRefresh(), null, null, null, null, new OwnedRunScope("test"));
    }

    /** One run, chunk after chunk: the ids it read and the cursor it leaves for the next run. */
    private record Run(List<Object> ids, ConnectorCursor cursor) {}

    @SuppressWarnings("unchecked")
    private Run run(ConnectorCursor from, Map<String, Object> config) {
        ConnectorContext ctx = ctx(config);
        List<Object> ids = new ArrayList<>();
        ConnectorCursor cursor = from;
        ReadResult<Map<String, Object>> r = reader().read(2, cursor, ctx);
        while (r instanceof ReadResult.HasMore<Map<String, Object>> more) {
            more.records().forEach(m -> ids.add(m.get("id")));
            cursor = more.nextCursor();
            r = reader().read(2, cursor, ctx);
        }
        return new Run(ids, cursor);
    }

    private void fourOrders() {
        api.updatedAt.put("SO-1", "2026-10-01T08:00:01.000Z");
        api.updatedAt.put("SO-2", "2026-10-01T08:00:02.000Z");
        api.updatedAt.put("SO-3", "2026-10-01T08:00:03.000Z");
        api.updatedAt.put("SO-4", "2026-10-01T08:00:04.000Z");
    }

    /** SO-1 changes once its page is read, SO-4 just after — still ahead, on the next page. */
    private void racingChanges() {
        api.afterPage = offset -> {
            if (offset == 0) {
                api.updatedAt.put("SO-1", "2026-10-23T09:46:07.156Z");
                api.updatedAt.put("SO-4", "2026-10-23T09:46:07.378Z");
            }
        };
    }

    @Test
    void a_change_made_during_a_run_reaches_the_next_run() {
        fourOrders();
        racingChanges();
        Run during = run(null, config(null));
        assertThat(during.ids()).containsExactly("SO-1", "SO-2", "SO-3", "SO-4");
        assertThat(during.cursor().value().get("watermark").asText())
            .as("the stored mark is the MAX read, unchanged").isEqualTo("2026-10-23T09:46:07.378Z");

        api.afterPage = offset -> {};
        Run next = run(during.cursor(), config(null));
        assertThat(next.ids()).contains("SO-1", "SO-4");
    }

    @Test
    void lookback_zero_is_the_mark_as_stored_and_loses_the_change() {
        fourOrders();
        racingChanges();
        Run during = run(null, config("0s"));
        api.afterPage = offset -> {};
        Run next = run(during.cursor(), config("0s"));
        assertThat(api.calls.getLast().queryParams()).containsEntry("updated_since", "2026-10-23T09:46:07.378Z");
        assertThat(next.ids()).containsExactly("SO-4");
    }

    @Test
    void a_declared_lookback_moves_the_sent_mark_back_by_that_much() {
        fourOrders();
        Run first = run(null, config("10m"));
        run(first.cursor(), config("10m"));
        assertThat(api.calls.getLast().queryParams()).containsEntry("updated_since", "2026-10-01T07:50:04.000Z");
    }

    @Test
    void a_mark_that_is_no_instant_is_sent_as_stored() {
        api.updatedAt.put("SO-1", "2026-10-01T08:00:01.000Z");
        Run first = run(null, config(null));
        ConnectorCursor dated = new ConnectorCursor(
            ((ObjectNode) first.cursor().value().deepCopy()).put("watermark", "2026-10-01"), Instant.parse("2026-10-23T00:00:00Z"));
        run(dated, config(null));
        assertThat(api.calls.getLast().queryParams()).containsEntry("updated_since", "2026-10-01");
    }

    @Test
    void marks_compare_by_what_they_are() {
        assertThat(RestEndpointReader.compareMarks("10", "9")).as("integers by value").isPositive();
        assertThat(RestEndpointReader.compareMarks("1000000000000000000", "999999999999999999"))
            .as("19 digits and more, by value").isPositive();
        assertThat(RestEndpointReader.compareMarks("123456789012345678901234567890", "99"))
            .as("past a long").isPositive();
        assertThat(RestEndpointReader.compareMarks("-10", "-9")).as("negatives").isNegative();
        assertThat(RestEndpointReader.compareMarks("007", "7")).as("leading zeros").isZero();
        assertThat(RestEndpointReader.compareMarks("1" + "0".repeat(1_000_000), "9".repeat(999_999)))
            .as("a million digits, compared without building a number").isPositive();
        assertThat(RestEndpointReader.compareMarks("2026-10-01T09:00:00Z", "2026-10-01T10:00:00+02:00"))
            .as("instants by time, whatever their offset").isPositive();
        assertThat(RestEndpointReader.compareMarks("v-b", "v-a")).as("anything else as text").isPositive();
    }

    @Test
    void the_moved_mark_keeps_the_offset_and_the_fraction_the_source_wrote() {
        // Simplify review of #1397: an API that reads only its own format still reads it.
        Duration tenMinutes = Duration.ofMinutes(10);
        assertThat(RestEndpointReader.sentWatermark("2026-10-23T11:46:07+02:00", tenMinutes, null))
            .isEqualTo("2026-10-23T11:36:07+02:00");
        assertThat(RestEndpointReader.sentWatermark("2026-10-23T09:46:07.378123Z", tenMinutes, null))
            .isEqualTo("2026-10-23T09:36:07.378123Z");
        assertThat(RestEndpointReader.sentWatermark("2026-10-23T09:46:07Z", null, 30L))
            .as("the run's length plus a minute").isEqualTo("2026-10-23T09:44:37Z");
    }
}
