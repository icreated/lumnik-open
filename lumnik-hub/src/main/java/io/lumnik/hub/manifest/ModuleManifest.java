package io.lumnik.hub.manifest;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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
    List<String> enums,
    List<String> tabs,
    @JsonProperty("enabled_by") String enabledBy
) {
    /** The one grammar of a tab id: a module manifest offers it, a kind:App declares it. */
    public static final Pattern TAB_ID = Pattern.compile("[a-z][a-z0-9-]{0,31}");

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

    /** Tab ids this module brings to the métier PWA; offered only while the module is active. */
    public List<String> tabsOrEmpty() {
        return tabs != null ? tabs : List.of();
    }

    public List<String> flywayLocations() {
        return flyway != null && flyway.locations() != null ? flyway.locations() : List.of();
    }
}
