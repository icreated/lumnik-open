package io.lumnik.hub.connector.rest.pagination;

import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;

import java.util.List;
import java.util.Map;

public class CustomPaginationStrategy implements PaginationStrategy {

    /** The validator refuses {@code kind: custom} with this; a connector applied before it still meets it at run time. */
    public static final String NOT_SUPPORTED =
        "custom pagination is not supported; use page, offset, cursor, link-header or body-link";

    private final String script;
    private final String responsePath;

    public static CustomPaginationStrategy fromInlineScript(String script, String responsePath) {
        return new CustomPaginationStrategy(script, responsePath);
    }

    public static CustomPaginationStrategy fromClasspath(String scriptRef, String responsePath) {
        throw new UnsupportedOperationException(NOT_SUPPORTED);
    }

    private CustomPaginationStrategy(String script, String responsePath) {
        this.script       = script;
        this.responsePath = responsePath;
    }

    @Override
    public RestRequest first(EndpointConfig cfg, Object incremental) {
        return PaginationSupport.baseGet(cfg);
    }

    @Override
    public RestRequest next(RestResponse resp, RestRequest current) {
        throw new UnsupportedOperationException(NOT_SUPPORTED);
    }

    @Override
    public List<Map<String, Object>> items(RestResponse resp) {
        return PaginationSupport.items(resp.json(), responsePath);
    }
}
