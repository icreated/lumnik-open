package io.lumnik.hub.connector.mapping.writer;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.common.tenant.TenantContext;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class DynamicTableRegistryIT {

  @Inject DynamicTableRegistry registry;

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  /** A registry row as {@link HybridJsonbWriter} leaves it: the {@code hybrid:} marker on the mapping. */
  private void registerHybrid(long tenant, String schema, String table) {
    Jdbc.update(adminDs, "DynamicTableRegistryIT.registerHybrid",
        "INSERT INTO connector.dynamic_table (tenant_id, mapping_id, table_schema, table_name, schema_inferred) "
            + "VALUES (?, ?, ?, ?, '{}'::jsonb)",
        ps -> {
          ps.setLong(1, tenant);
          ps.setString(2, "hybrid:" + schema + "." + table);
          ps.setString(3, schema);
          ps.setString(4, table);
        });
  }

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

  @Test
  void emitsRowEventsIsTrueForARegisteredHybridTable() {
    String table = "h_emits_" + System.nanoTime();
    registerHybrid(7002L, "ext", table);
    assertThat(registry.emitsRowEvents(7002L, "ext." + table)).isTrue();
  }

  @Test
  void emitsRowEventsIsTrueForARegisteredDynamicTable() {
    String table = "t_emits_" + System.nanoTime();
    TenantContext.runAsSystem(() -> registry.register(7002L, "emits-" + table, "connector", table, Map.of("a", "TEXT")));
    assertThat(registry.emitsRowEvents(7002L, "connector." + table)).isTrue();
  }

  @Test
  void emitsRowEventsIsFalseForAnUnregisteredExtName() {
    assertThat(registry.emitsRowEvents(7002L, "ext.never_registered_" + System.nanoTime())).isFalse();
  }

  @Test
  void emitsRowEventsIsFalseForAnotherTenantsHybridTable() {
    String table = "h_other_" + System.nanoTime();
    registerHybrid(7002L, "ext", table);
    assertThat(registry.emitsRowEvents(7003L, "ext." + table)).isFalse();
  }

  @Test
  void rowHashColumnIsRowHashForHybridAndUnderscoreForDynamic() {
    String hybrid = "h_hash_" + System.nanoTime();
    String dynamic = "t_hash_" + System.nanoTime();
    registerHybrid(7002L, "ext", hybrid);
    TenantContext.runAsSystem(() -> registry.register(7002L, "hash-" + dynamic, "connector", dynamic, Map.of("a", "TEXT")));
    assertThat(registry.rowHashColumn(7002L, "ext." + hybrid)).isEqualTo("row_hash");
    assertThat(registry.rowHashColumn(7002L, "connector." + dynamic)).isEqualTo("_row_hash");
  }

  @Test
  void rowHashColumnOfAnUnregisteredTableIsTheDynamicOne() {
    assertThat(registry.rowHashColumn(7002L, "ext.never_registered_" + System.nanoTime())).isEqualTo("_row_hash");
  }
}
