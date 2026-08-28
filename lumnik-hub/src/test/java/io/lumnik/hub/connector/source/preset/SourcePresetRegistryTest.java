package io.lumnik.hub.connector.source.preset;

import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plain unit test (no Quarkus): we stub the injected {@link Instance} so we can register a known set
 * of presets — including a deliberate duplicate — and drive {@link SourcePresetRegistry#index()} by hand.
 * The fixture is a local fake: the registry's mechanics must not depend on any vendor preset —
 * the real ones (e.g. event-hubs) live on the closed side and test their own expansion there.
 */
class SourcePresetRegistryTest {

    /** Minimal vendor preset: enough shape to drive every registry mechanic. */
    private static final class FakePreset implements SourcePreset {
        @Override public String kind() { return "test-vendor"; }

        @Override public List<String> requiredInputs() {
            return List.of("namespace", "connection_string_env");
        }

        @Override public Map<String, Object> expand(Map<String, Object> config) {
            return Map.of("kind", "kafka", "expanded_from", "test-vendor");
        }
    }

    @SuppressWarnings("unchecked")
    private static SourcePresetRegistry registryWith(SourcePreset... presets) {
        Instance<SourcePreset> beans = mock(Instance.class);
        when(beans.iterator()).thenReturn(List.of(presets).iterator());
        SourcePresetRegistry registry = new SourcePresetRegistry();
        registry.beans = beans;
        registry.index();
        return registry;
    }

    @Test
    void expand_delegates_to_the_matching_preset() {
        SourcePresetRegistry registry = registryWith(new FakePreset());

        Map<String, Object> out = registry.expand("test-vendor", Map.of("namespace", "my-ns"));

        assertEquals("kafka", out.get("kind"));
        assertEquals("test-vendor", out.get("expanded_from"));
    }

    @Test
    void is_preset_recognises_vendor_aliases_only() {
        SourcePresetRegistry registry = registryWith(new FakePreset());

        assertTrue(registry.isPreset("test-vendor"));
        assertFalse(registry.isPreset("kafka"));
    }

    @Test
    void unknown_kind_is_returned_unchanged() {
        SourcePresetRegistry registry = registryWith(new FakePreset());
        Map<String, Object> raw = Map.of("kind", "kafka", "topic", "orders");
        assertSame(raw, registry.expand("kafka", raw));
    }

    @Test
    void missingInputs_reportsBlankOrAbsentRequiredKeys() {
        SourcePresetRegistry registry = registryWith(new FakePreset());

        // complete spec → nothing missing
        assertTrue(registry.missingInputs("test-vendor", Map.of(
            "namespace", "my-ns", "connection_string_env", "MY_EH_CONN", "topic", "t", "name", "n"))
            .isEmpty());

        // absent namespace + blank connection_string_env → both flagged
        Map<String, Object> incomplete = new HashMap<>();
        incomplete.put("connection_string_env", "   ");
        incomplete.put("topic", "t");
        incomplete.put("name", "n");
        assertEquals(List.of("namespace", "connection_string_env"),
            registry.missingInputs("test-vendor", incomplete));
    }

    @Test
    void missingInputs_treatsNonStringValuesAsMissing() {
        SourcePresetRegistry registry = registryWith(new FakePreset());
        // a non-string namespace (e.g. YAML `namespace: 123` or a map) must NOT be substituted into a
        // broker template — report it as missing so the spec is rejected before expansion.
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("namespace", 123);
        cfg.put("connection_string_env", "MY_EH_CONN");
        assertEquals(List.of("namespace"), registry.missingInputs("test-vendor", cfg));
    }

    @Test
    void missingInputs_emptyForNonPreset() {
        SourcePresetRegistry registry = registryWith(new FakePreset());
        assertTrue(registry.missingInputs("kafka", Map.of()).isEmpty());
    }

    @Test
    void boot_time_duplicate_kind_guard_throws() {
        SourcePreset a = new FakePreset();
        SourcePreset b = new FakePreset(); // same kind() == "test-vendor"
        assertThrows(IllegalStateException.class, () -> registryWith(a, b));
    }
}
