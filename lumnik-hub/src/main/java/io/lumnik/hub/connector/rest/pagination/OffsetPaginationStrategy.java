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
        return at(PaginationSupport.baseGet(cfg), 0);
    }

    @Override
    public RestRequest next(RestResponse resp, RestRequest current) {
        int off = offsetOf(current);
        int n = items(resp).size();
        if (n < limit) return null;
        OptionalLong declared = PaginationSupport.declaredItems(resp.json(), totalPath);
        if (declared.isPresent() && off + n >= declared.getAsLong()) return null;
        return at(current, off + limit);
    }

    /**
     * One parameter named twice carries both, {@code offset,count} — PrestaShop's {@code limit=0,50} (#1754), MySQL's
     * {@code LIMIT o,n}. Named apart, each carries its own.
     */
    private boolean oneParameter() {
        return offsetParam.equals(limitParam);
    }

    private RestRequest at(RestRequest req, int offset) {
        return oneParameter() ? req.withQuery(offsetParam, offset + "," + limit)
            : req.withQuery(offsetParam, String.valueOf(offset)).withQuery(limitParam, String.valueOf(limit));
    }

    private int offsetOf(RestRequest req) {
        String v = req.queryParams().getOrDefault(offsetParam, "0");
        return Integer.parseInt(oneParameter() ? v.split(",", 2)[0] : v);
    }

    @Override
    public List<Map<String, Object>> items(RestResponse resp) {
        return PaginationSupport.items(resp.json(), responsePath);
    }
}
