package io.lumnik.hub.manifest;

/**
 * A module's verdict: installed is a fact of the build, active is computed ({@link ModuleStatuses}).
 * {@code inactiveReason} is null exactly when the module is active.
 */
public record ModuleStatus(ModuleManifest manifest, String inactiveReason) {
    public boolean active() {
        return inactiveReason == null;
    }
}
