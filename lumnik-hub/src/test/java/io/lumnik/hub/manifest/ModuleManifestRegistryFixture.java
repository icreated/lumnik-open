package io.lumnik.hub.manifest;

import java.util.List;
import java.util.function.Predicate;

/** Test support: a registry from a fabricated manifest set, through the package-private seam. */
public final class ModuleManifestRegistryFixture {
    private ModuleManifestRegistryFixture() {}

    public static ModuleManifestRegistry of(List<ModuleManifest> manifests, Predicate<ModuleManifest> allowed) {
        var r = new ModuleManifestRegistry();
        r.use(manifests, allowed);
        return r;
    }

    /** The one place a test spells the record's positional constructor; {@code permission} may be null. */
    public static ModuleManifest manifest(String id, List<String> depends, List<String> tabs, String permission) {
        var perms = permission == null ? null : List.of(new ModuleManifest.ManifestPermission(permission, null));
        return new ModuleManifest(1, id, "0.1.0", null, null, null, depends, null, null, null,
            perms, null, null, null, null, tabs, null);
    }

    /** A manifest that names the operator property switching it off ({@code enabled_by}). */
    public static ModuleManifest manifestEnabledBy(String id, List<String> depends, List<String> tabs, String enabledBy) {
        return new ModuleManifest(1, id, "0.1.0", null, null, null, depends, null, null, null,
            null, null, null, null, null, tabs, enabledBy);
    }
}
