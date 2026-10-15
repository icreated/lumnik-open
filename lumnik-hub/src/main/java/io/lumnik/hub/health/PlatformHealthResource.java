package io.lumnik.hub.health;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.lumnik.hub.manifest.ModuleStatus;
import io.lumnik.hub.manifest.ModuleManifestRegistry;
import io.lumnik.hub.search.SearchCapability;
import io.lumnik.hub.search.SearchGate;
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
    @Inject SearchGate searchGate;

    @GET
    public HealthResponse get() {
        Map<String, ModuleHealth> modules = registry.statuses().stream()
            .collect(Collectors.toMap(s -> s.manifest().id(), PlatformHealthResource::health));
        return new HealthResponse("ok", modules, searchHealth());
    }

    /** Every installed module is listed; an inactive one says so and why (D3). */
    static ModuleHealth health(ModuleStatus s) {
        return new ModuleHealth(s.active() ? "ok" : "inactive", s.manifest().version(), s.inactiveReason());
    }

    /** #703's own health scope (design doc §5): a Search outage never affects root readiness — the
     *  entire point of this field existing separately from `platform`. */
    private SearchHealth searchHealth() {
        SearchCapability cap = searchGate.capability();
        return switch (cap.state()) {
            case AVAILABLE -> new SearchHealth("ok", false, null);
            case UNAVAILABLE -> new SearchHealth("not_installed", false, null);
            case DEGRADED -> new SearchHealth("degraded", false, cap.reason());
        };
    }

    public record HealthResponse(String platform, Map<String, ModuleHealth> modules, SearchHealth search) {}

    public record ModuleHealth(String status, String version,
                               @JsonInclude(JsonInclude.Include.NON_NULL) String reason) {}

    public record SearchHealth(String status, boolean affectsReadiness, String error) {}
}
