package io.lumnik.hub.bff;

import io.lumnik.hub.manifest.ModuleManifest;

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
    List<ModuleSummary> modules
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
