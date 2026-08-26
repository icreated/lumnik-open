package io.lumnik.hub.manifest;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public record ModuleManifest(
    @JsonProperty("manifest_version") int manifestVersion,
    String id,
    String version,
    String schema,
    @JsonProperty("display_name") Map<String, String> displayName,
    Map<String, String> description,
    List<String> depends,
    @JsonProperty("api_base") String apiBase,
    ManifestFlyway flyway,
    List<ManifestMenu> menu,
    List<ManifestPermission> permissions,
    @JsonProperty("events_published") List<String> eventsPublished,
    @JsonProperty("events_consumed") List<String> eventsConsumed,
    List<String> screens,
    List<String> enums
) {
    public record ManifestFlyway(List<String> locations) {}

    public record ManifestMenu(
        String id,
        Map<String, String> label,
        String route,
        String icon,
        String permission,
        int order
    ) {}

    public record ManifestPermission(
        String id,
        Map<String, String> label
    ) {}

    public List<String> flywayLocations() {
        return flyway != null && flyway.locations() != null ? flyway.locations() : List.of();
    }
}
