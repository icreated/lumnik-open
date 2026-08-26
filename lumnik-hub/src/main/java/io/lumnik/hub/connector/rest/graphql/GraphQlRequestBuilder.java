package io.lumnik.hub.connector.rest.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.Urls;

import java.util.Map;

/**
 * Builds the first GraphQL request: an HTTP POST of {@code {"query": ..., "variables": ...}}
 * to {@code base_url + graphql_path}. The relay pagination loop advances by re-injecting the
 * cursor into {@code variables} via {@link io.lumnik.hub.connector.rest.pagination.VariableCursorBinding}.
 */
public final class GraphQlRequestBuilder {

    private static final ObjectMapper M = new ObjectMapper();

    private GraphQlRequestBuilder() {}

    public static RestRequest first(String baseUrl, String graphqlPath, String query, Map<String, Object> variables) {
        ObjectNode root = M.createObjectNode();
        root.put("query", query);
        root.set("variables", variables == null ? M.createObjectNode() : M.valueToTree(variables));
        try {
            // GraphQL servers require an explicit JSON content type; the JDK HttpClient sets none.
            // Headers survive the relay loop (withBody copies them) and a user-declared header wins.
            return RestRequest.post(resolveUrl(baseUrl, graphqlPath), M.writeValueAsString(root))
                .withHeader("Content-Type", "application/json");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build GraphQL request body", e);
        }
    }

    /** Resolves the GraphQL URL: a blank {@code graphql_path} defaults to {@code /graphql}, then
     *  {@link Urls#join} normalizes the slash between {@code base_url} and the path. */
    static String resolveUrl(String baseUrl, String graphqlPath) {
        String path = (graphqlPath == null || graphqlPath.isBlank()) ? "/graphql" : graphqlPath.trim();
        return Urls.join(baseUrl, path);
    }
}
