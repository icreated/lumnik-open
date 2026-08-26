package io.lumnik.hub.connector.rest.pagination.preset;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

@ApplicationScoped
public class SpringSlicePreset implements PaginationPreset {
    @Override public String name() { return "spring-slice"; }
    @Override public Map<String, Object> expansion() {
        return Map.of("kind", "page", "page_param", "page", "size_param", "size",
            "start_page", "0", "last_path", "$.last", "response_path", "$.content");
    }
}
