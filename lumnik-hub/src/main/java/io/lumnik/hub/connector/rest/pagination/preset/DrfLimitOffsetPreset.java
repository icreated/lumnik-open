package io.lumnik.hub.connector.rest.pagination.preset;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

@ApplicationScoped
public class DrfLimitOffsetPreset implements PaginationPreset {
    @Override public String name() { return "drf-limit-offset"; }
    @Override public Map<String, Object> expansion() {
        return Map.of("kind", "offset", "offset_param", "offset", "limit_param", "limit",
            "total_path", "$.count", "response_path", "$.results");
    }
}
