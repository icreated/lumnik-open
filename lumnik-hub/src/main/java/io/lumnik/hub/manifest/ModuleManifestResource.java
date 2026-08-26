package io.lumnik.hub.manifest;

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
        List<ModuleDto> modules = registry.all().stream()
            .map(m -> new ModuleDto(
                m.id(),
                m.version(),
                displayName(m),
                m.depends() != null ? m.depends() : List.of(),
                m.menu() != null ? m.menu() : List.of(),
                m.permissions() != null ? m.permissions() : List.of(),
                m.eventsPublished() != null ? m.eventsPublished() : List.of(),
                true
            ))
            .toList();

        return new ModulesResponse(
            new PlatformInfo("0.1.0", Instant.now().toString()),
            modules
        );
    }

    private String displayName(ModuleManifest m) {
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
        boolean active
    ) {}
}
