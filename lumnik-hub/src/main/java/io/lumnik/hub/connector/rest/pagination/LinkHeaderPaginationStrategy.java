package io.lumnik.hub.connector.rest.pagination;

import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LinkHeaderPaginationStrategy implements PaginationStrategy {

    private static final Pattern LINK = Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"([^\"]+)\"");

    private final String rel, responsePath;

    public LinkHeaderPaginationStrategy(Map<String, String> c) {
        this.rel          = c.getOrDefault("rel", "next");
        this.responsePath = c.getOrDefault("response_path", "$");
    }

    @Override
    public RestRequest first(EndpointConfig cfg, Object incremental) {
        return PaginationSupport.baseGet(cfg);
    }

    @Override
    public RestRequest next(RestResponse resp, RestRequest current) {
        Optional<String> link = resp.header("Link");
        if (link.isEmpty()) return null;
        Matcher m = LINK.matcher(link.get());
        while (m.find()) {
            // The Link header came from the upstream response — untrusted. Vet it before following (SSRF).
            if (rel.equalsIgnoreCase(m.group(2))) return current.withUrl(PaginationUrlGuard.vet(m.group(1), current.url()));
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<Map<String, Object>> items(RestResponse resp) {
        Object items = JsonPathExtractor.read(resp.json(), responsePath);
        if (items instanceof List<?> l) return (List<Map<String, Object>>) (List<?>) l;
        if (items instanceof Map<?, ?> m) return List.of((Map<String, Object>) (Map<?, ?>) m);
        return List.of();
    }
}
