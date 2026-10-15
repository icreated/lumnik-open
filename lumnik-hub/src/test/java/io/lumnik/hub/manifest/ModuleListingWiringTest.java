package io.lumnik.hub.manifest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The modules door lists every INSTALLED module with its verdict, not only the active ones (D3). No
 * running hub has an inactive module in slice 1, so this is where the door is proved.
 */
class ModuleListingWiringTest {

    @Test
    void the_door_lists_an_inactive_module_with_its_reason_and_tabs() {
        var door = new ModuleManifestResource();
        door.registry = ModuleManifestRegistryFixture.of(List.of(
            ModuleManifestRegistryFixture.manifest("core", null, List.of("fiches"), null),
            ModuleManifestRegistryFixture.manifest("rag", List.of("llm"), List.of("chat"), null)), m -> true);

        var modules = door.get().modules();

        assertThat(modules).extracting(ModuleManifestResource.ModuleDto::id).containsExactly("core", "rag");
        assertThat(modules.get(0).active()).isTrue();
        assertThat(modules.get(0).inactiveReason()).isNull();
        var rag = modules.get(1);
        assertThat(rag.active()).isFalse();
        assertThat(rag.inactiveReason()).isEqualTo("missing_dependency:llm");
        assertThat(rag.tabs()).containsExactly("chat");
        assertThat(rag.depends()).containsExactly("llm");
    }
}
