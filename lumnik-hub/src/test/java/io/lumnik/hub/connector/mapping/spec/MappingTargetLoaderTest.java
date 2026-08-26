package io.lumnik.hub.connector.mapping.spec;

import io.lumnik.hub.connector.mapping.MappingValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MappingTargetLoaderTest {

  @Test
  void legacy_string_target_becomes_jpa() {
    MappingSpec spec = MappingLoader.load("""
      id: legacy
      version: 1
      target: business_partner.BusinessPartner
      """);
    MappingTarget t = spec.targets();
    assertThat(t.jpa()).isNotNull();
    assertThat(t.jpa().entity()).isEqualTo("business_partner.BusinessPartner");
    assertThat(t.rag()).isNull();
    assertThat(t.table()).isNull();
  }

  @Test
  void multi_target_with_rag_and_table() {
    MappingSpec spec = MappingLoader.load("""
      id: m
      version: 1
      target:
        rag:
          corpus: shopify-customers
          chunk-template: 'Customer ${name} from ${country}'
          metadata:
            source: shopify
        table:
          schema: tenant_data
          name: customers
          auto-schema: true
          on-conflict: skip
      """);
    MappingTarget t = spec.targets();
    assertThat(t.rag()).isNotNull();
    assertThat(t.rag().corpus()).isEqualTo("shopify-customers");
    assertThat(t.rag().chunkTemplate()).contains("Customer ${name}");
    assertThat(t.rag().metadata()).containsEntry("source", "shopify");
    assertThat(t.table()).isNotNull();
    assertThat(t.table().schema()).isEqualTo("tenant_data");
    assertThat(t.table().name()).isEqualTo("customers");
    assertThat(t.table().autoSchema()).isTrue();
    assertThat(t.table().onConflict()).isEqualTo(MappingTarget.OnConflict.SKIP);
  }

  @Test
  void target_can_combine_all_three() {
    MappingSpec spec = MappingLoader.load("""
      id: m
      version: 1
      target:
        rag: { corpus: c, chunk-template: 't' }
        table: { schema: s, name: n }
        jpa: { entity: pkg.Entity }
      """);
    MappingTarget t = spec.targets();
    assertThat(t.rag()).isNotNull();
    assertThat(t.table()).isNotNull();
    assertThat(t.jpa()).isNotNull();
  }

  /**
   * This is layer 2 of the DDL-injection fix (see {@code RlsDdl}): the manifest boundary must
   * refuse a {@code schema}/{@code name} that is not a plain identifier BEFORE it can reach the
   * DDL that {@code DynamicTableWriter}/{@code HybridJsonbWriter} execute on the BYPASSRLS admin
   * datasource.
   */
  @Test
  void rejects_a_table_name_that_is_not_a_plain_identifier() {
    assertThatThrownBy(() -> MappingLoader.load("""
      id: m
      version: 1
      target:
        table:
          schema: s
          name: "o'r"
      """))
      .isInstanceOf(MappingValidationException.class)
      .hasMessageContaining("name");
  }

  @Test
  void rejects_a_schema_that_is_not_a_plain_identifier() {
    assertThatThrownBy(() -> MappingLoader.load("""
      id: m
      version: 1
      target:
        table:
          schema: "s$$evil"
          name: n
      """))
      .isInstanceOf(MappingValidationException.class)
      .hasMessageContaining("schema");
  }

  @Test
  void table_indexes_parsed() {
    MappingSpec spec = MappingLoader.load("""
      id: m
      version: 1
      target:
        table:
          schema: s
          name: n
          indexes:
            - { columns: [email], unique: true }
            - { columns: [country] }
      """);
    var idx = spec.targets().table().indexes();
    assertThat(idx).hasSize(2);
    assertThat(idx.get(0).columns()).containsExactly("email");
    assertThat(idx.get(0).unique()).isTrue();
    assertThat(idx.get(1).unique()).isFalse();
  }
}
