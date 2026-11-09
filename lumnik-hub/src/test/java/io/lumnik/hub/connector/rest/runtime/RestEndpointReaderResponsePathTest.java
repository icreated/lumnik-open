package io.lumnik.hub.connector.rest.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.hub.connector.api.types.SyncMode;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.rest.RestHttpClient;
import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;
import io.lumnik.hub.connector.rest.pagination.PaginationFactory;
import io.lumnik.hub.connector.rest.reliability.RateLimiterRegistry;
import io.lumnik.hub.connector.runtime.OwnedRunScope;
import io.lumnik.hub.connector.secret.SecretRefs;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * #1806: {@code response_path} says where the rows are. A path that reads nothing used to read as an empty
 * page — a preset applied to an API that spells the field {@code content} instead of {@code items} ended
 * Completed with zero rows. On the first page a missing path, or one that is not a list, fails the run; a
 * list that is really empty stays a success.
 */
class RestEndpointReaderResponsePathTest {

    /** Answers the queued bodies in order. */
    static final class QueuedHttp extends RestHttpClient {
        final Deque<String> bodies = new ArrayDeque<>();

        @Override public RestResponse execute(RestRequest req) {
            return RestResponse.of(200, Map.of(), bodies.removeFirst());
        }
    }

    private final ConnectorEndpoint endpoint = new ConnectorEndpoint();
    private final QueuedHttp http = new QueuedHttp();

    {
        endpoint.setName("partners");
        endpoint.setMethod("GET");
        endpoint.setPath("/partners");
    }

    private ReadResult<Map<String, Object>> read(String... bodies) {
        http.bodies.addAll(List.of(bodies));
        RestEndpointReader reader = new RestEndpointReader(endpoint, new RestEndpointReaderChunkingTest.PassThroughAuth(),
            new PaginationFactory(), new RateLimiterRegistry(), http, new ObjectMapper(),
            SecretRefs.environmentOnly(name -> null));
        ConnectorContext ctx = new ConnectorContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1L, "erp",
            "rest-generic",
            Map.of("base_url", "https://erp.test", "default_rate_limit", "100000/s",
                "pagination", Map.of("kind", "offset", "offset_param", "offset", "limit_param", "limit", "limit", 2,
                    "response_path", "$.items")),
            new SyncMode.FullRefresh(), null, null, null, null, new OwnedRunScope("test"));
        return reader.read(1000, null, ctx);
    }

    private static List<Map<String, Object>> rows(ReadResult<Map<String, Object>> result) {
        return switch (result) {
            case ReadResult.HasMore<Map<String, Object>> more -> more.records();
            case ReadResult.EndOfStream<Map<String, Object>> end -> end.records();
            case ReadResult.EmptyButOpen<Map<String, Object>> open -> List.<Map<String, Object>>of();
        };
    }

    @Test
    void a_first_page_without_the_key_fails_the_run() {
        assertThatThrownBy(() -> read("{\"content\":[{\"id\":1}]}"))
            .isInstanceOf(ConnectorException.class)
            .hasMessage("Endpoint 'partners': pagination.response_path reads nothing on the first page — check it against"
                + " the source's answer (or use the source's preset)");
    }

    @Test
    void a_first_page_with_something_else_than_a_list_fails_the_run() {
        assertThatThrownBy(() -> read("{\"items\":{\"id\":1}}"))
            .isInstanceOf(ConnectorException.class)
            .hasMessage("Endpoint 'partners': pagination.response_path must read a list, not an object");
    }

    @Test
    void a_list_that_is_really_empty_is_a_success_with_zero_rows() {
        assertThat(rows(read("{\"items\":[]}"))).isEmpty();
    }

    @Test
    void a_later_page_without_the_key_ends_the_read_without_failing_it() {
        assertThat(rows(read("{\"items\":[{\"id\":1},{\"id\":2}]}", "{}"))).hasSize(2);
    }
}
