package io.lumnik.hub.connector.rest.pagination.preset;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

@ApplicationScoped
public class NestJsPreset implements PaginationPreset {
    @Override public String name() { return "nestjs"; }
    @Override public Map<String, Object> expansion() {
        return Map.of("kind", "body-link", "link_path", "$.links.next", "response_path", "$.data");
    }
}
