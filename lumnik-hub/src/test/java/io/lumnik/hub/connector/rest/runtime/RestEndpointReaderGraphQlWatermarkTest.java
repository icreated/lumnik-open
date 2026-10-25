package io.lumnik.hub.connector.rest.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A graphql endpoint in watermark mode: the mark reaches the server as the GraphQL variable named by
 * {@code incremental.param}, inside the POST body — a URL query parameter is ignored by a GraphQL server,
 * and every run then reads everything.
 */
class RestEndpointReaderGraphQlWatermarkTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A GraphQL server: filters on {@code $updated_since}, pages by {@code $after}, two nodes a page. */
    static final class GraphQlApi extends RestHttpClient {
        final Map<String, String> updatedAt = new LinkedHashMap<>();
        final List<RestRequest> calls = new ArrayList<>();

        @Override public RestResponse execute(RestRequest req) throws Exception {
            calls.add(req);
            JsonNode vars = MAPPER.readTree(req.body()).path("variables");
            String since = vars.path("updated_since").isTextual() ? vars.path("updated_since").asText() : null;
            int start = vars.path("after").isTextual() ? Integer.parseInt(vars.path("after").asText()) + 1 : 0;
            List<Map<String, Object>> rows = new ArrayList<>();
            updatedAt.forEach((id, at) -> {
                if (since == null || at.compareTo(since) >= 0) rows.add(Map.of("id", id, "updated_at", at));
            });
            List<Map<String, Object>> page = rows.subList(Math.min(start, rows.size()), Math.min(start + 2, rows.size()));
            List<Map<String, Object>> edges = new ArrayList<>();
            for (Map<String, Object> node : page) edges.add(Map.of("node", node));
            boolean more = start + 2 < rows.size();
            Map<String, Object> body = Map.of("data", Map.of("partners", Map.of("edges", edges,
                "pageInfo", Map.of("hasNextPage", more, "endCursor", String.valueOf(start + page.size() - 1)))));
            return RestResponse.of(200, Map.of(), MAPPER.writeValueAsString(body));
        }
    }

    private final GraphQlApi api = new GraphQlApi();

    private RestEndpointReader reader() {
        AuthProviderRegistry none = new AuthProviderRegistry() {
            @Override public AuthProvider build(AuthSpec spec) { return r -> r; }
        };
        ConnectorEndpoint ep = new ConnectorEndpoint();
        ep.setName("partners");
        ep.setMethod("POST");
        ep.setPath("/graphql");
        return new RestEndpointReader(ep, none, new PaginationFactory(), new RateLimiterRegistry(), api, MAPPER,
            SecretRefs.environmentOnly(name -> null));
    }

    private static Map<String, Object> config() {
        return Map.of("base_url", "https://acme.test", "kind", "graphql",
            "query", "query($after: String, $updated_since: String) { partners(first: 2, after: $after, "
                + "updatedSince: $updated_since) { edges { node { id updated_at } } pageInfo { hasNextPage endCursor } } }",
            "incremental", Map.of("mode", "watermark", "param", "updated_since",
                "watermark_path", "$.updated_at", "lookback", "0s"),
            "pagination", Map.of("kind", "relay", "cursor_binding", "variable", "cursor_var", "after",
                "cursor_path", "$.data.partners.pageInfo.endCursor",
                "has_more_path", "$.data.partners.pageInfo.hasNextPage",
                "response_path", "$.data.partners.edges[*].node"));
    }

    private static ConnectorContext ctx() {
        return new ConnectorContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1L, "erp", "rest-generic",
            config(), new SyncMode.FullRefresh(), null, null, null, null, new OwnedRunScope("test"));
    }

    private record Run(List<Object> ids, ConnectorCursor cursor) {}

    private Run run(ConnectorCursor from) {
        ConnectorContext ctx = ctx();
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

    private static JsonNode variables(RestRequest req) throws Exception {
        return MAPPER.readTree(req.body()).path("variables");
    }

    @Test
    void the_mark_is_sent_as_the_named_variable_on_every_page() throws Exception {
        api.updatedAt.put("P-1", "2026-10-01T08:00:01Z");
        api.updatedAt.put("P-2", "2026-10-01T08:00:02Z");
        api.updatedAt.put("P-3", "2026-10-01T08:00:03Z");
        api.updatedAt.put("P-4", "2026-10-01T08:00:04Z");
        Run first = run(null);
        assertThat(first.ids()).containsExactly("P-1", "P-2", "P-3", "P-4");
        assertThat(variables(api.calls.getFirst()).has("updated_since")).as("no mark yet, none sent").isFalse();

        ConnectorCursor older = new ConnectorCursor(((ObjectNode) first.cursor().value().deepCopy())
            .put("watermark", "2026-10-01T08:00:02Z"), Instant.parse("2026-10-23T00:00:00Z"));
        api.calls.clear();
        Run second = run(older);

        assertThat(second.ids()).containsExactly("P-2", "P-3", "P-4");
        assertThat(api.calls).hasSize(2);
        for (RestRequest call : api.calls) {
            assertThat(variables(call).path("updated_since").asText()).isEqualTo("2026-10-01T08:00:02Z");
            assertThat(call.queryParams()).as("a GraphQL server reads no URL parameter").isEmpty();
        }
    }
}
