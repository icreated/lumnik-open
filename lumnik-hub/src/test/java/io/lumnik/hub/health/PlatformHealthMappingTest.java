package io.lumnik.hub.health;

import io.lumnik.extraction.spi.search.SearchAvailability;
import io.lumnik.hub.manifest.ModuleManifest;
import io.lumnik.hub.manifest.ModuleManifestRegistryFixture;
import io.lumnik.hub.manifest.ModuleStatus;
import io.lumnik.hub.search.SearchCapability;
import io.lumnik.hub.search.SearchGate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformHealthMappingTest {

    private static ModuleManifest m(String id, List<String> depends) {
        return ModuleManifestRegistryFixture.manifest(id, depends, null, null);
    }

    @Test
    void an_active_module_is_ok_without_a_reason() {
        var h = PlatformHealthResource.health(new ModuleStatus(m("rag", null), null));
        assertThat(h.status()).isEqualTo("ok");
        assertThat(h.reason()).isNull();
    }

    @Test
    void an_inactive_module_says_so_and_why() {
        var h = PlatformHealthResource.health(new ModuleStatus(m("rag", null), "disabled"));
        assertThat(h.status()).isEqualTo("inactive");
        assertThat(h.reason()).isEqualTo("disabled");
    }

    @Test
    void the_door_lists_every_installed_module_including_the_inactive_one() {
        var door = new PlatformHealthResource();
        door.registry = ModuleManifestRegistryFixture.of(
            List.of(m("core", null), m("rag", List.of("llm"))), x -> true); // llm is not carried
        door.searchGate = new SearchGate() {
            @Override public SearchCapability capability() {
                return new SearchCapability(SearchAvailability.UNAVAILABLE, null, "not_installed", null);
            }
        };
        var modules = door.get().modules();
        assertThat(modules).containsOnlyKeys("core", "rag");
        assertThat(modules.get("rag").status()).isEqualTo("inactive");
    }
}
