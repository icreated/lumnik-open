package io.lumnik.hub.health;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.lumnik.hub.ask.ReadonlyHardening;
import io.lumnik.hub.manifest.ModuleStatus;
import io.lumnik.hub.manifest.ModuleManifestRegistry;
import io.lumnik.hub.search.SearchCapability;
import io.lumnik.hub.search.SearchGate;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Path("/api/platform/health")
@Produces(MediaType.APPLICATION_JSON)
public class PlatformHealthResource {

    @Inject ModuleManifestRegistry registry;
    @Inject SearchGate searchGate;
    @Inject ReadonlyHardening hardening;

    @GET
    public HealthResponse get() {
        List<ModuleStatus> statuses = registry.statuses();
        Map<String, ModuleHealth> modules = statuses.stream()
            .collect(Collectors.toMap(s -> s.manifest().id(), PlatformHealthResource::health));
        ModuleStatus rag = statuses.stream().filter(s -> "rag".equals(s.manifest().id())).findFirst().orElse(null);
        return new HealthResponse("ok", modules, searchHealth(), ask(rag, hardening::stillOpen));
    }

    /**
     * #1987: analytic Ask rides the rag module, so absence and an operator's switch come first, as for Search; then it is
     * switched off while the readonly role holds a privilege it must not. The hub itself stays "ok" and readiness is not
     * touched — ingestion, views and the rest still serve. Which privileges stays in the log.
     */
    static AskHealth ask(ModuleStatus rag, Supplier<List<String>> stillOpen) {
        if (rag == null) return new AskHealth("not_installed", null);
        if (!rag.active()) return new AskHealth("inactive", rag.inactiveReason());
        List<String> open = stillOpen.get();
        if (open.isEmpty()) return new AskHealth("ok", null);
        // a check that could not run (pool, password, network) is not a privilege to revoke
        return new AskHealth("inactive", ReadonlyHardening.CHECK_FAILED.equals(open)
            ? "readonly-role-check-failed" : "readonly-role-not-hardened");
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

    public record HealthResponse(String platform, Map<String, ModuleHealth> modules, SearchHealth search, AskHealth ask) {}

    public record ModuleHealth(String status, String version,
                               @JsonInclude(JsonInclude.Include.NON_NULL) String reason) {}

    public record SearchHealth(String status, boolean affectsReadiness, String error) {}

    public record AskHealth(String status, @JsonInclude(JsonInclude.Include.NON_NULL) String reason) {}
}
