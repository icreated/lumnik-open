package io.lumnik.hub.manifest;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.time.Instant;
import java.util.List;

@Path("/api/platform/modules")
@Produces(MediaType.APPLICATION_JSON)
public class ModuleManifestResource {

    @Inject ModuleManifestRegistry registry;

    @GET
    public ModulesResponse get() {
        List<ModuleDto> modules = registry.statuses().stream()
            .map(ModuleManifestResource::dto)
            .toList();

        return new ModulesResponse(
            new PlatformInfo("0.1.0", Instant.now().toString()),
            modules
        );
    }

    /** Every installed module with its verdict; a module that is off is still listed (D3). */
    static ModuleDto dto(ModuleStatus s) {
        ModuleManifest m = s.manifest();
        return new ModuleDto(
            m.id(),
            m.version(),
            displayName(m),
            m.depends() != null ? m.depends() : List.of(),
            m.menu() != null ? m.menu() : List.of(),
            m.permissions() != null ? m.permissions() : List.of(),
            m.eventsPublished() != null ? m.eventsPublished() : List.of(),
            s.active(),
            s.inactiveReason(),
            m.tabsOrEmpty()
        );
    }

    private static String displayName(ModuleManifest m) {
        if (m.displayName() == null) return m.id();
        return m.displayName().getOrDefault("en", m.id());
    }

    public record ModulesResponse(PlatformInfo platform, List<ModuleDto> modules) {}

    public record PlatformInfo(String version, String build) {}

    public record ModuleDto(
        String id,
        String version,
        String displayName,
        List<String> depends,
        List<ModuleManifest.ManifestMenu> menus,
        List<ModuleManifest.ManifestPermission> permissions,
        List<String> eventsPublished,
        boolean active,
        @JsonInclude(JsonInclude.Include.NON_NULL) String inactiveReason,
        List<String> tabs
    ) {}
}
