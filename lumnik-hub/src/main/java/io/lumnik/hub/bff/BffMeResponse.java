package io.lumnik.hub.bff;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.lumnik.hub.manifest.ModuleManifest;
import io.lumnik.hub.view.MapTiles;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record BffMeResponse(
    Long userId,
    String login,
    String name,
    String email,
    TenantSummary tenant,
    Set<String> roles,
    List<ModuleSummary> modules,
    String edition,
    List<String> tabs,
    @JsonInclude(JsonInclude.Include.NON_NULL) MapTiles.Background map
) {
    public record TenantSummary(Long id, String code, String name) {}

    public record ModuleSummary(
        String id,
        Map<String, String> displayName,
        String apiBase,
        List<ModuleManifest.ManifestMenu> menu
    ) {
        static ModuleSummary from(ModuleManifest m) {
            return new ModuleSummary(m.id(), m.displayName(), m.apiBase(), m.menu());
        }
    }
}
