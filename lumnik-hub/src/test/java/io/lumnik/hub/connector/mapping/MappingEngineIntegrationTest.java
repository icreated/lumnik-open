package io.lumnik.hub.connector.mapping;

import io.lumnik.hub.connector.mapping.spec.MappingLoader;
import io.lumnik.hub.connector.mapping.spec.MappingSpec;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying that:
 * 1. MappingEngine is injectable via CDI
 * 2. HookCatalog discovers TestAddMarkerHook (top-level test class annotated @HookId)
 * 3. MappingEngine dispatches to the hook and the output row carries the injected marker
 */
@QuarkusTest
class MappingEngineIntegrationTest {

  @Inject MappingEngine engine;

  @Test
  void registered_test_hook_is_discovered_and_executed() {
    MappingSpec spec = MappingLoader.load("""
        id: test
        version: 1
        target: x
        dataset:
          - { test-add-marker: {} }
        """);

    List<Map<String, Object>> input = List.of(new HashMap<>(Map.of("a", "1")));
    var output = engine.transform(spec, input, new MappingState(), false);

    assertThat(output).hasSize(1);
    assertThat(output.get(0)).containsEntry("marker", "added-by-test-hook");
    assertThat(output.get(0)).containsEntry("a", "1");
  }
}
