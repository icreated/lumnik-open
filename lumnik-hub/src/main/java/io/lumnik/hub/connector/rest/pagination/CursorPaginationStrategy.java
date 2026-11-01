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
        this.hasMorePath  = PaginationSupport.optional(cfg.get("has_more_path"));
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

    /**
     * The next page while there is one. With {@code has_more_path} the flag decides, and a flag saying "more" with no
     * cursor is refused. Without it the cursor decides (#1611) — and an empty page ends the read, whatever cursor it
     * carries, or a source handing a fresh token past its last row would be followed to MAX_PAGES.
     */
    @Override
    public RestRequest next(RestResponse resp, RestRequest current) {
        if (hasMorePath == null) {
            String cursor = PaginationSupport.next(resp.json(), cursorPath, "cursor_path");
            return cursor == null || items(resp).isEmpty() ? null : binding.bind(current, cursor);
        }
        if (!Boolean.TRUE.equals(PaginationSupport.flag(resp.json(), hasMorePath, "has_more_path"))) return null;
        return binding.bind(current, PaginationSupport.cursorWhileMore(resp.json(), cursorPath));
    }

    @Override
    public List<Map<String, Object>> items(RestResponse resp) {
        return PaginationSupport.items(resp.json(), responsePath);
    }
}
