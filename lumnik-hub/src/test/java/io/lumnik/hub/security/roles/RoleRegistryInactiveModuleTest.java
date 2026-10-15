package io.lumnik.hub.security.roles;

import io.lumnik.hub.manifest.ModuleManifestRegistryFixture;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** D2 witness: a module that is inactive is still installed, so its permissions stay known to the roles. */
class RoleRegistryInactiveModuleTest {

    @Test
    void an_inactive_modules_permissions_are_still_known() {
        var rag = ModuleManifestRegistryFixture.manifest("rag", List.of("llm"), List.of("chat"), "rag.index");
        var registry = ModuleManifestRegistryFixture.of(List.of(rag), m -> true); // llm is not carried: rag is inactive

        assertThat(registry.active()).isEmpty();
        assertThat(RoleRegistry.collectKnownPermissions(registry)).contains("rag.index");
    }
}
