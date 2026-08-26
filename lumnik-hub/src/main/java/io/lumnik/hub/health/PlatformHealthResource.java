package io.lumnik.hub.health;

import io.lumnik.hub.manifest.ModuleManifest;
import io.lumnik.hub.manifest.ModuleManifestRegistry;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;
import java.util.stream.Collectors;

@Path("/api/platform/health")
@Produces(MediaType.APPLICATION_JSON)
public class PlatformHealthResource {

    @Inject ModuleManifestRegistry registry;

    @GET
    public HealthResponse get() {
        Map<String, ModuleHealth> modules = registry.all().stream()
            .collect(Collectors.toMap(
                ModuleManifest::id,
                m -> new ModuleHealth("ok", m.version())
            ));
        return new HealthResponse("ok", modules);
    }

    public record HealthResponse(String platform, Map<String, ModuleHealth> modules) {}

    public record ModuleHealth(String status, String version) {}
}
