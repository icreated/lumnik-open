package io.lumnik.hub.connector.rest.pagination;

import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;

import java.util.List;
import java.util.Map;

public class CustomPaginationStrategy implements PaginationStrategy {

    private final String script;
    private final String responsePath;

    public static CustomPaginationStrategy fromInlineScript(String script, String responsePath) {
        return new CustomPaginationStrategy(script, responsePath);
    }

    public static CustomPaginationStrategy fromClasspath(String scriptRef, String responsePath) {
        throw new UnsupportedOperationException(
            "script_ref loader: wire to mapping-engine hook loader in Task 9 integration");
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
        throw new UnsupportedOperationException(
            "Custom Kotlin pagination scheduled for Task 9 — KotlinScriptHost.executeSource integration");
    }

    @Override
    public List<Map<String, Object>> items(RestResponse resp) {
        return PaginationSupport.items(resp.json(), responsePath);
    }
}
