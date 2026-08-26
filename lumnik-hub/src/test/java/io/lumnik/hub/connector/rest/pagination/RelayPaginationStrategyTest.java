package io.lumnik.hub.connector.rest.pagination;
import io.lumnik.hub.connector.rest.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RelayPaginationStrategyTest {
    RelayPaginationStrategy s = new RelayPaginationStrategy(Map.of(
        "cursor_param", "after", "cursor_path", "$.data.x.pageInfo.endCursor",
        "has_more_path", "$.data.x.pageInfo.hasNextPage", "response_path", "$.data.x.nodes"));
    @Test void uses_relay_cursors() {
        RestResponse r = RestResponse.of(200, Map.of(),
            "{\"data\":{\"x\":{\"pageInfo\":{\"endCursor\":\"Y2:42\",\"hasNextPage\":true},\"nodes\":[{}]}}}");
        RestRequest next = s.next(r, RestRequest.get("https://x"));
        assertEquals("Y2:42", next.queryParams().get("after"));
    }

    @Test
    void graphql_first_builds_post_body_when_query_present() throws Exception {
        var c = new java.util.HashMap<String, String>();
        c.put("cursor_binding", "variable");
        c.put("cursor_var", "after");
        c.put("cursor_path", "$.data.orders.pageInfo.endCursor");
        c.put("has_more_path", "$.data.orders.pageInfo.hasNextPage");
        c.put("response_path", "$.data.orders.edges[*].node");
        var strategy = new RelayPaginationStrategy(c);

        var raw = new java.util.HashMap<String, Object>();
        raw.put("query", "query Orders($after:String){ orders }");
        raw.put("variables", Map.of("first", 100));
        raw.put("graphql_path", "/api/graphql");
        var ec = new io.lumnik.hub.connector.rest.pagination.EndpointConfig(
            "http://shop", "POST", "", raw);

        io.lumnik.hub.connector.rest.RestRequest req = strategy.first(ec, null);

        Assertions.assertEquals("POST", req.method());
        Assertions.assertEquals("http://shop/api/graphql", req.url());
        var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(req.body());
        Assertions.assertEquals(
            "query Orders($after:String){ orders }", body.path("query").asText());
    }
}
