package io.lumnik.hub.connector.rest.pagination.preset;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

@ApplicationScoped
public class JsonApiPreset implements PaginationPreset {
    @Override public String name() { return "json-api"; }
    @Override public Map<String, Object> expansion() {
        return Map.of("kind", "body-link", "link_path", "$.links.next", "response_path", "$.data");
    }
}
