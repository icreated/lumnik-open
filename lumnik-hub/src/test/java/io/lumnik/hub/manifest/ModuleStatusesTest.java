package io.lumnik.hub.manifest;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleStatusesTest {

    private static ModuleManifest m(String id, String... depends) {
        return ModuleManifestRegistryFixture.manifest(id, depends.length == 0 ? null : List.of(depends), null, null);
    }

    private static Map<String, ModuleStatus> byId(List<ModuleManifest> ms, Predicate<ModuleManifest> allowed) {
        var out = new LinkedHashMap<String, ModuleStatus>();
        ModuleStatuses.compute(ms, allowed).forEach(s -> out.put(s.manifest().id(), s));
        return out;
    }

    @Test
    void a_module_with_no_dependency_is_active() {
        var s = byId(List.of(m("core")), x -> true).get("core");
        assertThat(s.active()).isTrue();
        assertThat(s.inactiveReason()).isNull();
    }

    @Test
    void an_unknown_dependency_makes_the_module_inactive_with_a_reason_and_never_throws() {
        var s = byId(List.of(m("rag", "llm")), x -> true).get("rag");
        assertThat(s.active()).isFalse();
        assertThat(s.inactiveReason()).isEqualTo("missing_dependency:llm");
    }

    @Test
    void an_inactive_dependency_propagates() {
        var all = byId(List.of(m("llm"), m("rag", "llm")), x -> !x.id().equals("llm"));
        assertThat(all.get("llm").inactiveReason()).isEqualTo("disabled");
        assertThat(all.get("rag").inactiveReason()).isEqualTo("inactive_dependency:llm");
    }

    @Test
    void a_cycle_and_a_self_dependency_deactivate_every_member() {
        var all = byId(List.of(m("a", "b"), m("b", "a"), m("c", "c")), x -> true);
        assertThat(all.values()).allSatisfy(s -> {
            assertThat(s.active()).isFalse();
            assertThat(s.inactiveReason()).isEqualTo("dependency_cycle");
        });
    }

    @Test
    void a_module_depending_on_a_cycle_member_is_inactive_through_its_dependency() {
        var all = byId(List.of(m("a", "b"), m("b", "a"), m("d", "a")), x -> true);
        assertThat(all.get("d").inactiveReason()).isEqualTo("inactive_dependency:a");
    }

    @Test
    void disabled_beats_a_cycle() {
        var c = byId(List.of(m("c", "c")), x -> false).get("c");
        assertThat(c.inactiveReason()).isEqualTo("disabled");
    }

    @Test
    void with_two_failing_dependencies_the_first_in_declaration_order_names_the_reason() {
        var off = m("off");   // present but disallowed -> inactive
        var a = byId(List.of(off, m("x", "ghost", "off")), z -> !z.id().equals("off")).get("x");
        var b = byId(List.of(off, m("x", "off", "ghost")), z -> !z.id().equals("off")).get("x");
        assertThat(a.inactiveReason()).isEqualTo("missing_dependency:ghost");
        assertThat(b.inactiveReason()).isEqualTo("inactive_dependency:off");
    }

    @Test
    void a_null_dependency_is_a_missing_one_never_an_absent_one() {
        var s = byId(List.of(ModuleManifestRegistryFixture.manifest("rag", Arrays.asList("llm", null), null, null),
            m("llm")), x -> true).get("rag");
        assertThat(s.active()).isFalse();
        assertThat(s.inactiveReason()).isEqualTo("missing_dependency:null");

        var only = byId(List.of(ModuleManifestRegistryFixture.manifest("rag", Arrays.asList((String) null), null, null)),
            x -> true).get("rag");
        assertThat(only.inactiveReason()).isEqualTo("missing_dependency:null");
    }

    @Test
    void statuses_keep_the_input_order() {
        var ids = ModuleStatuses.compute(List.of(m("b"), m("a")), x -> true).stream()
            .map(s -> s.manifest().id()).toList();
        assertThat(ids).containsExactly("b", "a");
    }

    @Test
    void a_disabled_module_names_the_property_that_switched_it_off() {
        var llm = ModuleManifestRegistryFixture.manifestEnabledBy("llm", null, null, "lumnik.ai.enabled");
        var rag = ModuleManifestRegistryFixture.manifestEnabledBy("rag", List.of("llm"), null, "lumnik.ai.enabled");
        var all = byId(List.of(llm, rag), x -> false);
        assertThat(all.get("llm").inactiveReason()).isEqualTo("disabled:lumnik.ai.enabled");
        // disabled outranks the dependency: rag is switched off by the same property, not "inactive_dependency"
        assertThat(all.get("rag").inactiveReason()).isEqualTo("disabled:lumnik.ai.enabled");
    }
}
