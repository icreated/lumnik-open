package io.lumnik.hub.connector.rest.pagination;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.Urls;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

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

    /**
     * The row count a source publishes at {@code totalPath} — the witness a run is measured against
     * once its pagination ends. Empty when the manifest names no path, and empty when the path is
     * there but the body does not carry it: an absent count read as 0 would refuse every run.
     *
     * <p>Spelled once because three strategies consume it (offset, page, body-link) and a rule
     * written three times is a rule that can disagree with itself.
     */
    public static OptionalLong declaredItems(JsonNode json, String totalPath) {
        // Blank counts as unset, the way an optional path key already does one class over
        // (PagePaginationStrategy's size_param). A templated manifest or a YAML default leaves
        // `total_path: ""` behind meaning "none named"; compiling it instead throws and fails every
        // run of an endpoint whose only sin is an empty optional field.
        if (totalPath == null || totalPath.isBlank()) return OptionalLong.empty();
        Number total = JsonPathExtractor.read(json, totalPath);
        return total == null ? OptionalLong.empty() : OptionalLong.of(total.longValue());
    }

    /** The first request: a GET to {@code base_url + path} (one-slash join), or the null-cfg sentinel. */
    public static RestRequest baseGet(EndpointConfig cfg) {
        return cfg == null ? RestRequest.get("about:blank")
            : RestRequest.get(Urls.join(cfg.baseUrl(), cfg.path()));
    }
}
