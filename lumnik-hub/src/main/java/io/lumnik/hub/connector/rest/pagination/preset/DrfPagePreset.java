package io.lumnik.hub.connector.rest.pagination.preset;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

@ApplicationScoped
public class DrfPagePreset implements PaginationPreset {
    @Override public String name() { return "drf-page"; }
    @Override public Map<String, Object> expansion() {
        return Map.of("kind", "body-link", "link_path", "$.next", "response_path", "$.results");
    }
}
