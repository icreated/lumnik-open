package io.lumnik.hub.connector.mapping.writer;

import io.lumnik.common.tenant.TenantContext;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class DynamicTableRegistryIT {

  @Inject DynamicTableRegistry registry;

  @Test
  void register_and_find_round_trip() {
    String mappingId = "test-mapping-" + System.nanoTime();
    Map<String, String> schema = Map.of("email", "TEXT", "name", "TEXT", "age", "NUMERIC(18,4)");

    TenantContext.runAsSystem(() -> {
      registry.register(1L, mappingId, "tenant_1_data", "customers", schema);
    });

    Map<String, String> found = TenantContext.callAsSystem(() ->
        registry.findSchema(1L, mappingId, null));
    assertThat(found).containsAllEntriesOf(schema);
  }

  @Test
  void find_unknown_returns_null() {
    Map<String, String> found = TenantContext.callAsSystem(() ->
        registry.findSchema(999L, "nonexistent-" + System.nanoTime(), null));
    assertThat(found).isNull();
  }

  @Test
  void register_twice_updates_schema() {
    String mappingId = "twice-" + System.nanoTime();
    TenantContext.runAsSystem(() -> {
      registry.register(1L, mappingId, "tenant_1_data", "t1", Map.of("a", "TEXT"));
      registry.register(1L, mappingId, "tenant_1_data", "t1", Map.of("a", "TEXT", "b", "TEXT"));
    });
    Map<String, String> found = TenantContext.callAsSystem(() -> registry.findSchema(1L, mappingId, null));
    assertThat(found).containsKeys("a", "b");
  }
}
