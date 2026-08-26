package io.lumnik.hub.connector.rest.pagination;

import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;

import java.util.List;
import java.util.Map;

public class CursorPaginationStrategy implements PaginationStrategy {

    private final String cursorPath, hasMorePath, responsePath;
    private final CursorBinding binding;

    public CursorPaginationStrategy(Map<String, String> cfg) {
        this.cursorPath   = cfg.get("cursor_path");
        this.hasMorePath  = cfg.get("has_more_path");
        this.responsePath = cfg.get("response_path");
        String kind = cfg.getOrDefault("cursor_binding", "query");
        this.binding = switch (kind) {
            case "query"  -> new QueryParamCursorBinding(cfg.get("cursor_param"));
            case "header" -> new HeaderCursorBinding(cfg.get("cursor_param"));
            default       -> throw new IllegalArgumentException("Unknown cursor_binding: " + kind);
        };
    }

    @Override
    public RestRequest first(EndpointConfig cfg, Object incremental) {
        return PaginationSupport.baseGet(cfg);
    }

    @Override
    public RestRequest next(RestResponse resp, RestRequest current) {
        Boolean hasMore = JsonPathExtractor.read(resp.json(), hasMorePath);
        if (!Boolean.TRUE.equals(hasMore)) return null;
        Object cursorVal = JsonPathExtractor.read(resp.json(), cursorPath);
        if (cursorVal instanceof List<?> l && !l.isEmpty()) cursorVal = l.get(0);
        if (cursorVal == null) return null;
        return binding.bind(current, String.valueOf(cursorVal));
    }

    @Override
    public List<Map<String, Object>> items(RestResponse resp) {
        return PaginationSupport.items(resp.json(), responsePath);
    }
}
