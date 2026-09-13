package io.lumnik.hub.connector.rest.pagination;

import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

public class OffsetPaginationStrategy implements PaginationStrategy {

    private final String offsetParam, limitParam, responsePath, totalPath;
    private final int limit;

    public OffsetPaginationStrategy(Map<String, String> c) {
        this.offsetParam  = c.get("offset_param");
        this.limitParam   = c.get("limit_param");
        this.limit        = Integer.parseInt(c.getOrDefault("limit", "100"));
        this.totalPath    = c.get("total_path");
        this.responsePath = c.get("response_path");
    }

    @Override
    public RestRequest first(EndpointConfig cfg, Object incremental) {
        return PaginationSupport.baseGet(cfg)
            .withQuery(offsetParam, "0").withQuery(limitParam, String.valueOf(limit));
    }

    @Override
    public RestRequest next(RestResponse resp, RestRequest current) {
        int off = Integer.parseInt(current.queryParams().getOrDefault(offsetParam, "0"));
        int n = items(resp).size();
        if (n < limit) return null;
        OptionalLong declared = PaginationSupport.declaredItems(resp.json(), totalPath);
        if (declared.isPresent() && off + n >= declared.getAsLong()) return null;
        return current.withQuery(offsetParam, String.valueOf(off + limit));
    }

    @Override
    public List<Map<String, Object>> items(RestResponse resp) {
        return PaginationSupport.items(resp.json(), responsePath);
    }
}
