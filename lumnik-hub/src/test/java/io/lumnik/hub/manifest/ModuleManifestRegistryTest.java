package io.lumnik.hub.manifest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.function.Predicate;

/** D2: the registry reports, it does not switch — {@code all()} stays "installed". */
class ModuleManifestRegistryTest {

    private static ModuleManifest m(String id, List<String> depends, List<String> tabs, String permission) {
        return ModuleManifestRegistryFixture.manifest(id, depends, tabs, permission);
    }

    private static ModuleManifestRegistry registryWith(List<ModuleManifest> ms, Predicate<ModuleManifest> allowed) {
        return ModuleManifestRegistryFixture.of(ms, allowed);
    }

    @Test
    void an_inactive_module_stays_installed() {
        var core = m("core", null, List.of("fiches", "inbox"), null);
        var rag = m("rag", List.of("llm"), List.of("chat"), "rag.index"); // llm is not carried
        var r = registryWith(List.of(core, rag), x -> true);

        assertThat(r.all()).extracting(ModuleManifest::id).containsExactly("core", "rag");
        assertThat(r.active()).extracting(ModuleManifest::id).containsExactly("core");
        assertThat(r.statuses().get(1).inactiveReason()).isEqualTo("missing_dependency:llm");
    }

    @Test
    void offered_tabs_are_the_sorted_union_over_active_modules_only() {
        var r = registryWith(List.of(
            m("core", null, List.of("inbox", "fiches"), null),
            m("llm", null, null, null),
            m("rag", List.of("llm"), List.of("chat"), null),
            m("ghost", List.of("nowhere"), List.of("map"), null)), x -> true);
        assertThat(r.offeredTabs()).containsExactly("chat", "fiches", "inbox");
    }

    @Test
    void a_malformed_tab_id_is_skipped_not_fatal() {
        var r = registryWith(List.of(m("core", null, List.of("fiches", "Bad Id", "", "x".repeat(40)), null)), x -> true);
        assertThat(r.offeredTabs()).containsExactly("fiches");
    }

    @Test
    void a_module_the_predicate_refuses_offers_nothing() {
        var r = registryWith(List.of(m("core", null, List.of("fiches"), null),
            m("rag", null, List.of("chat"), null)), x -> !x.id().equals("rag"));
        assertThat(r.offeredTabs()).containsExactly("fiches");
        assertThat(r.statuses().get(1).inactiveReason()).isEqualTo("disabled");
    }

    private static Function<String, Optional<String>> config(Map<String, String> values) {
        return name -> Optional.ofNullable(values.get(name));
    }

    private static ModuleManifest ai(String id, List<String> depends, List<String> tabs) {
        return ModuleManifestRegistryFixture.manifestEnabledBy(id, depends, tabs, "lumnik.ai.enabled");
    }

    @Test
    void allowed_by_lets_through_a_module_that_names_no_property() {
        assertThat(ModuleManifestRegistry.allowedBy(config(Map.of())).test(m("core", null, null, null))).isTrue();
    }

    @Test
    void allowed_by_lets_through_an_absent_property_and_an_explicit_true() {
        assertThat(ModuleManifestRegistry.allowedBy(config(Map.of())).test(ai("rag", null, null))).isTrue();
        assertThat(ModuleManifestRegistry.allowedBy(config(Map.of("lumnik.ai.enabled", "true")))
            .test(ai("rag", null, null))).isTrue();
    }

    @Test
    void allowed_by_refuses_a_module_whose_property_is_false() {
        assertThat(ModuleManifestRegistry.allowedBy(config(Map.of("lumnik.ai.enabled", "false")))
            .test(ai("rag", null, null))).isFalse();
    }

    @Test
    void any_other_value_stops_the_hub_instead_of_being_read_two_ways() {
        // @LookupIfProperty compares the exact string "true"; a boolean parser would also accept these.
        // Two readers, two answers: the hub refuses to start rather than half-disable the family.
        for (String bad : List.of("TRUE", "yes", "1", "on", "", " false")) {
            var allowed = ModuleManifestRegistry.allowedBy(config(Map.of("lumnik.ai.enabled", bad)));
            assertThatThrownBy(() -> allowed.test(ai("rag", null, null)))
                .as("value '%s'", bad)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lumnik.ai.enabled")
                .hasMessageContaining("true")
                .hasMessageContaining("false");
        }
    }

    @Test
    void a_hub_with_the_ai_off_keeps_both_modules_installed_and_offers_no_chat() {
        var r = registryWith(List.of(
            m("core", null, List.of("fiches", "inbox"), null),
            ai("llm", null, null),
            ai("rag", List.of("llm"), List.of("chat"))),
            ModuleManifestRegistry.allowedBy(config(Map.of("lumnik.ai.enabled", "false"))));

        assertThat(r.all()).extracting(ModuleManifest::id).containsExactly("core", "llm", "rag");
        assertThat(r.active()).extracting(ModuleManifest::id).containsExactly("core");
        assertThat(r.offeredTabs()).containsExactly("fiches", "inbox");
        assertThat(r.statuses()).extracting(ModuleStatus::inactiveReason)
            .containsExactly(null, "disabled:lumnik.ai.enabled", "disabled:lumnik.ai.enabled");
    }

    @Test
    void the_switch_is_validated_even_when_no_installed_module_names_it() {
        // An Open build carries no module with enabled_by; a typo must still stop the hub there.
        assertThatThrownBy(() -> ModuleManifestRegistry.validateSwitches(config(Map.of("lumnik.ai.enabled", "FALSE"))))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("lumnik.ai.enabled");
        ModuleManifestRegistry.validateSwitches(config(Map.of()));
        ModuleManifestRegistry.validateSwitches(config(Map.of("lumnik.ai.enabled", "false")));
    }
}
