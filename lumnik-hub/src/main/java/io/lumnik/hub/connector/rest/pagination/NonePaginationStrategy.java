package io.lumnik.hub.connector.rest.pagination;

import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;

import java.util.List;
import java.util.Map;

/**
 * An endpoint that answers its whole list at once — the usual shape of an older application (#1650):
 * one request, never a next page. {@code response_path} says where the list is.
 */
public class NonePaginationStrategy implements PaginationStrategy {

    private final String responsePath;

    public NonePaginationStrategy(Map<String, String> c) {
        this.responsePath = c.get("response_path");
        if (responsePath == null || responsePath.isBlank()) {
            throw new IllegalArgumentException("none pagination requires 'response_path'");
        }
    }

    @Override
    public RestRequest first(EndpointConfig cfg, Object incremental) {
        return PaginationSupport.baseGet(cfg);
    }

    @Override
    public RestRequest next(RestResponse resp, RestRequest current) {
        return null;
    }

    @Override
    public List<Map<String, Object>> items(RestResponse resp) {
        return PaginationSupport.items(resp.json(), responsePath);
    }
}
