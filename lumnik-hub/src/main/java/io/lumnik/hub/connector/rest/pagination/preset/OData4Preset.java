package io.lumnik.hub.connector.rest.pagination.preset;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

/** OData v4: members named {@code @odata.…}, so the paths are bracketed; the endpoint's path asks for {@code $count=true}. */
@ApplicationScoped
public class OData4Preset implements PaginationPreset {
    @Override public String name() { return "odata-v4"; }
    @Override public Map<String, Object> expansion() {
        return Map.of("kind", "body-link", "link_path", "$['@odata.nextLink']", "response_path", "$.value",
            "total_path", "$['@odata.count']");   // sent only with $count=true (#1827)
    }
}
