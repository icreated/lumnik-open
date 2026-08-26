package io.lumnik.hub.connector.mapping;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.mapping.spec.MappingLoader;
import io.lumnik.hub.connector.mapping.spec.MappingSpec;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@SuppressWarnings("java:S2699") // no_targets_doesnt_crash is a "does not throw" test
class MultiTargetIT {

  @Inject MappingEngine engine;
  @Inject EntityManager em;
  @Inject UserTransaction utx;

  @Test
  void writes_to_dynamic_table_when_table_target_set() throws Exception {
    String mappingId = "multi_target_table_" + System.nanoTime();
    String yaml = """
      id: %s
      version: 1
      target:
        table:
          schema: tenant_1_data
          name: %s
          auto-schema: true
      cell:
        - { clean: { columns: ['*'], strip: true } }
      """.formatted(mappingId, mappingId);

    MappingSpec spec = MappingLoader.load(yaml);
    List<Map<String, Object>> input = List.of(
        new HashMap<String,Object>(Map.of("name", "Alice", "email", "a@x.com")),
        new HashMap<String,Object>(Map.of("name", "Bob", "email", "b@x.com")));

    TenantContext.runAsSystem(() -> {
      try {
        utx.begin();
        engine.transformAndWrite(spec, input, 1L, UUID.randomUUID(), true, null, List.of());
        utx.commit();
      } catch (Exception e) { throw new RuntimeException(e); }
    });

    long tableCount = ((Number) em.createNativeQuery(
        "SELECT count(*) FROM tenant_1_data." + mappingId).getSingleResult()).longValue();
    assertThat(tableCount).isEqualTo(2);
  }

  @Test
  void no_targets_doesnt_crash() throws Exception {
    // JPA-only target with non-existent entity should NOT crash the writer in v1
    // (JPA writer is opt-in late; v1 just skips)
    String mappingId = "jpa_only_" + System.nanoTime();
    String yaml = """
      id: %s
      version: 1
      target: { jpa: { entity: nonexistent.Entity } }
      """.formatted(mappingId);
    MappingSpec spec = MappingLoader.load(yaml);
    List<Map<String, Object>> input = List.of(new HashMap<String,Object>(Map.of("a", "1")));
    TenantContext.runAsSystem(() -> {
      try {
        utx.begin();
        engine.transformAndWrite(spec, input, 1L, UUID.randomUUID(), true, null, List.of());
        utx.commit();
      } catch (Exception e) { throw new RuntimeException(e); }
    });
    // No exception = success
  }
}
