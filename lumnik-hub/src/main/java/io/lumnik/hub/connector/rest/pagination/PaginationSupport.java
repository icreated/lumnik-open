package io.lumnik.hub.connector.rest.pagination;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.Urls;

import java.util.List;
import java.util.Map;

/**
 * Shared bits the pagination strategies all need: extracting the item list from a response body via
 * {@code response_path}, and building the first GET to {@code base_url + path}. Keeps the strategies
 * free of the boilerplate they otherwise copy verbatim.
 */
public final class PaginationSupport {

    private PaginationSupport() {}

    /** The rows at {@code responsePath} as a list of maps; empty when the path is absent or not a list. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> items(JsonNode json, String responsePath) {
        Object items = JsonPathExtractor.read(json, responsePath);
        return items instanceof List<?> l ? (List<Map<String, Object>>) (List<?>) l : List.of();
    }

    /** The first request: a GET to {@code base_url + path} (one-slash join), or the null-cfg sentinel. */
    public static RestRequest baseGet(EndpointConfig cfg) {
        return cfg == null ? RestRequest.get("about:blank")
            : RestRequest.get(Urls.join(cfg.baseUrl(), cfg.path()));
    }
}
