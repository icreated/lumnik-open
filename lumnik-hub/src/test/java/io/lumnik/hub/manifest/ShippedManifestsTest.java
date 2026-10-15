package io.lumnik.hub.manifest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Inventory over the manifests on the hub's own classpath (core, identity, and whatever the build
 * carries: the default Pro build, so rag and llm are here, and a Search build adds its provider).
 * Each module also pins its own manifest in its own module (rag, llm, the two Search providers),
 * which is what covers the packagings this classpath does not have.
 */
class ShippedManifestsTest {

    /** The registry's own scan and validation, not a copy of it. */
    private static List<ModuleManifest> shipped() {
        var registry = new ModuleManifestRegistry();
        registry.onStart(null);
        return registry.all();
    }

    @Test
    void every_declared_tab_id_is_well_formed() {
        for (ModuleManifest m : shipped()) {
            assertThat(m.tabsOrEmpty()).as("tabs of %s", m.id()).allMatch(t -> ModuleManifest.TAB_ID.matcher(t).matches());
        }
    }

    @Test
    void core_declares_the_fundamental_tabs() {
        var core = shipped().stream().filter(m -> "core".equals(m.id())).findFirst().orElseThrow();
        assertThat(core.tabsOrEmpty()).containsExactly("fiches", "inbox");
    }

    @Test
    void a_module_that_is_here_declares_its_chat_with_its_dependency() {
        shipped().stream().filter(m -> "rag".equals(m.id())).forEach(m -> {
            assertThat(m.tabsOrEmpty()).containsExactly("chat");
            assertThat(m.depends()).containsExactly("llm");
        });
    }

    @Test
    void every_depends_names_a_module_that_is_carried() {
        var all = shipped();
        var ids = all.stream().map(ModuleManifest::id).collect(Collectors.toSet());
        for (ModuleManifest m : all) {
            assertThat(ids).as("depends of %s", m.id()).containsAll(m.depends() == null ? List.of() : m.depends());
        }
    }

    @Test
    void the_ai_family_names_the_switch_and_nobody_else_does() {
        for (ModuleManifest m : shipped()) {
            boolean ai = "rag".equals(m.id()) || "llm".equals(m.id());
            assertThat(m.enabledBy()).as("enabled_by of %s", m.id())
                .isEqualTo(ai ? "lumnik.ai.enabled" : null);
        }
    }
}
