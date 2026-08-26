package io.lumnik.hub.connector.rest.graphql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.rest.RestRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GraphQlRequestBuilderTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void builds_post_with_query_and_variables() throws Exception {
        RestRequest req = GraphQlRequestBuilder.first(
            "http://api", "/graphql", "query Orders { x }", Map.of("first", 100));

        assertEquals("POST", req.method());
        assertEquals("http://api/graphql", req.url());
        assertEquals("application/json", req.headers().get("Content-Type"));
        JsonNode body = M.readTree(req.body());
        assertEquals("query Orders { x }", body.path("query").asText());
        assertEquals(100, body.path("variables").path("first").asInt());
    }

    @Test
    void emits_empty_variables_object_when_null() throws Exception {
        RestRequest req = GraphQlRequestBuilder.first("http://api", "/graphql", "q", null);

        assertTrue(M.readTree(req.body()).path("variables").isObject());
    }

    @Test
    void prepends_missing_leading_slash_on_path() {
        // graphql_path without a leading slash must not glue onto the host (http://apigraphql).
        RestRequest req = GraphQlRequestBuilder.first("http://api", "graphql", "q", null);
        assertEquals("http://api/graphql", req.url());
    }

    @Test
    void avoids_double_slash_when_base_has_trailing_slash() {
        RestRequest req = GraphQlRequestBuilder.first("http://api/", "/graphql", "q", null);
        assertEquals("http://api/graphql", req.url());
    }
}
