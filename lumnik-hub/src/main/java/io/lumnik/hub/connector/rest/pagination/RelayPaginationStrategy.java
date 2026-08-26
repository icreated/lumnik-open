package io.lumnik.hub.connector.rest.pagination;

import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;
import io.lumnik.hub.connector.rest.graphql.GraphQlRequestBuilder;

import java.util.List;
import java.util.Map;

public class RelayPaginationStrategy implements PaginationStrategy {

    private static final String QUERY = "query";

    private final String cursorPath, hasMorePath, responsePath;
    private final CursorBinding binding;

    public RelayPaginationStrategy(Map<String, String> c) {
        this.cursorPath   = c.get("cursor_path");
        this.hasMorePath  = c.get("has_more_path");
        this.responsePath = c.get("response_path");
        String kind = c.getOrDefault("cursor_binding", QUERY);
        this.binding = switch (kind) {
            case QUERY      -> new QueryParamCursorBinding(c.get("cursor_param"));
            case "header"   -> new HeaderCursorBinding(c.get("cursor_param"));
            case "variable" -> new VariableCursorBinding(c.get("cursor_var"));
            default         -> throw new IllegalArgumentException("Unknown cursor_binding: " + kind);
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    public RestRequest first(EndpointConfig cfg, Object incremental) {
        if (cfg == null) return RestRequest.get("about:blank");
        Object query = cfg.raw().get("query");
        if (query != null) {                                  // GraphQL request — query plus variables
            Object vars = cfg.raw().get("variables");
            Object gqlPath = cfg.raw().get("graphql_path");
            return GraphQlRequestBuilder.first(
                cfg.baseUrl(),
                gqlPath instanceof String s ? s : "/graphql",
                String.valueOf(query),
                vars instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of());
        }
        return PaginationSupport.baseGet(cfg);   // REST relay: GET
    }

    @Override
    public RestRequest next(RestResponse resp, RestRequest current) {
        Boolean hm = JsonPathExtractor.read(resp.json(), hasMorePath);
        if (!Boolean.TRUE.equals(hm)) return null;
        Object cur = JsonPathExtractor.read(resp.json(), cursorPath);
        if (cur == null) return null;
        return binding.bind(current, String.valueOf(cur));
    }

    @Override
    public List<Map<String, Object>> items(RestResponse resp) {
        return PaginationSupport.items(resp.json(), responsePath);
    }
}
