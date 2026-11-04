package io.lumnik.hub.connector.rest.pagination.preset;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

/** Spring Data's PagedModel — a Page under VIA_DTO serialization (#1642): the counters sit under "page". */
@ApplicationScoped
public class SpringPagedModelPreset implements PaginationPreset {
    @Override public String name() { return "spring-paged-model"; }
    @Override public Map<String, Object> expansion() {
        return Map.of("kind", "page", "page_param", "page", "size_param", "size",
            "start_page", "0", "total_pages_path", "$.page.totalPages", "response_path", "$.content",
            "total_path", "$.page.totalElements");
    }
}
