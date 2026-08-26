package io.lumnik.hub.connector.rest.pagination.preset;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

@ApplicationScoped
public class FastApiPreset implements PaginationPreset {
    @Override public String name() { return "fastapi"; }
    @Override public Map<String, Object> expansion() {
        return Map.of("kind", "page", "page_param", "page", "size_param", "size",
            "start_page", "1", "total_pages_path", "$.pages", "response_path", "$.items");
    }
}
