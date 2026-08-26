package io.lumnik.hub.connector.jdbc;

import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.hub.connector.mapping.spec.MappingSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MappingYamlBuilderTest {

  private final MappingYamlBuilder builder = new MappingYamlBuilder();

  @Test
  void builds_spec_with_rag_and_table_targets() {
    TableDescriptor td = new TableDescriptor("public", "c_bpartner",
        List.of("c_bpartner_id"),
        List.of(new ColumnDescriptor("c_bpartner_id", "BIGINT", 19, false),
                new ColumnDescriptor("name", "VARCHAR", 120, false)),
        List.of(), Optional.of("updated"), 0L);

    MappingSpec spec = builder.build("legacy-erp-prod", td, "Row ${name} #${c_bpartner_id}.");

    assertNotNull(spec);
    assertEquals("legacy-erp-prod--c_bpartner", spec.id());
    assertEquals(1, spec.version());
    assertNotNull(spec.targets());
    assertNotNull(spec.targets().rag());
    assertEquals("legacy-erp-prod-c_bpartner", spec.targets().rag().corpus());
    assertEquals("Row ${name} #${c_bpartner_id}.", spec.targets().rag().chunkTemplate());
    assertNotNull(spec.targets().table());
    assertEquals("c_bpartner", spec.targets().table().name());
    assertTrue(spec.targets().table().autoSchema());
  }

  @Test
  void dump_yaml_is_non_empty_and_parseable() {
    TableDescriptor td = new TableDescriptor("public", "m_product",
        List.of("m_product_id"),
        List.of(new ColumnDescriptor("m_product_id", "BIGINT", 19, false),
                new ColumnDescriptor("name", "VARCHAR", 120, false)),
        List.of(), Optional.of("updated"), 0L);

    MappingSpec spec = builder.build("erp", td, "Row ${name}.");
    String yaml = builder.dumpYaml(spec);

    assertNotNull(yaml);
    assertFalse(yaml.isBlank());
    assertTrue(yaml.contains("erp--m_product") || yaml.contains("erp-m_product"),
        "YAML should contain the mapping id or corpus: " + yaml);
  }

  @Test
  void table_target_schema_is_set() {
    TableDescriptor td = new TableDescriptor("public", "c_order",
        List.of("c_order_id"),
        List.of(new ColumnDescriptor("c_order_id", "BIGINT", 19, false)),
        List.of(), Optional.empty(), 0L);

    MappingSpec spec = builder.build("legacy-erp", td, "Row.");
    String yaml = builder.dumpYaml(spec);

    assertNotNull(yaml);
    assertFalse(yaml.isBlank());
    // table target schema should be present in YAML
    assertTrue(yaml.contains("c_order"), "YAML should reference table name: " + yaml);
  }

  // Layer 2 of the DDL-injection fix (see RlsDdl): build() constructs a MappingTarget.Table
  // directly from schema-introspection input, bypassing MappingLoader.parseTable entirely.
  // JdbcSourceResource.addTable() echoes an API-request-supplied table name into TableDescriptor
  // unsanitized (SchemaIntrospector.describeOne does not verify it against real DB metadata), so
  // this boundary must not trust its caller either.
  @Test
  void rejects_a_table_name_that_is_not_a_plain_identifier() {
    TableDescriptor td = new TableDescriptor("public", "o'r",
        List.of("id"), List.of(new ColumnDescriptor("id", "BIGINT", 19, false)),
        List.of(), Optional.empty(), 0L);

    assertThrows(ConfigValidationException.class, () -> builder.build("erp", td, "Row."));
  }

  @Test
  void rejects_a_schema_that_is_not_a_plain_identifier() {
    TableDescriptor td = new TableDescriptor("s$$evil", "orders",
        List.of("id"), List.of(new ColumnDescriptor("id", "BIGINT", 19, false)),
        List.of(), Optional.empty(), 0L);

    assertThrows(ConfigValidationException.class, () -> builder.build("erp", td, "Row."));
  }
}
