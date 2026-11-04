package io.lumnik.hub.connector.rest.pagination.preset;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class SpringHateoasPreset implements PaginationPreset {
    @Override public String name() { return "spring-hateoas"; }
    @Override public Map<String, Object> expansion() {
        return Map.of("kind", "body-link", "link_path", "$._links.next.href",
            "response_path", "$._embedded.{items}",
            "total_path", "$.page.totalElements");
    }
    @Override public Set<String> requiredOverrides() { return Set.of("items"); }
}
