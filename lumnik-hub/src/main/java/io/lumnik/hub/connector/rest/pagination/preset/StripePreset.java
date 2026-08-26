package io.lumnik.hub.connector.rest.pagination.preset;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

@ApplicationScoped
public class StripePreset implements PaginationPreset {
    @Override public String name() { return "stripe"; }
    @Override public Map<String, Object> expansion() {
        return Map.of("kind", "cursor", "cursor_param", "starting_after",
            "cursor_path", "$.data[-1:].id", "has_more_path", "$.has_more",
            "response_path", "$.data");
    }
}
