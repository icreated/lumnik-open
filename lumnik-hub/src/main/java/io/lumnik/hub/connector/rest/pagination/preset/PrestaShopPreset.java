package io.lumnik.hub.connector.rest.pagination.preset;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Set;

/**
 * PrestaShop's Webservice (#1754): {@code limit=offset,count} in one parameter, no total, the list named after the
 * resource ({@code products}, {@code categories}…) — read with {@code output_format=JSON} in the path.
 */
@ApplicationScoped
public class PrestaShopPreset implements PaginationPreset {
    @Override public String name() { return "prestashop"; }
    @Override public Map<String, Object> expansion() {
        return Map.of("kind", "offset", "offset_param", "limit", "limit_param", "limit",
            "response_path", "$.{resource}");
    }
    @Override public Set<String> requiredOverrides() { return Set.of("resource"); }
}
