package io.lumnik.hub.connector.mapping.spec;

import io.lumnik.hub.connector.mapping.MappingValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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

  /** #1441: a metadata value is a text template; a list used to be flattened to "" in silence. */
  @Test
  void rejects_a_rag_metadata_value_that_is_a_list() {
    assertThatThrownBy(() -> MappingLoader.load("""
      id: m
      version: 1
      target:
        rag:
          corpus: c
          metadata: { source: shopify, tags: [a, b] }
      """))
      .isInstanceOf(MappingValidationException.class)
      .hasMessage("target.rag.metadata: 'tags' must be text, a number or a boolean (got array)");
  }

  /** #1465: corpus, chunk-template and embedding-model are text; a list or a map used to be flattened to "". */
  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
      "corpus: [a]                       | 'corpus' must be text, a number or a boolean (got array)",
      "corpus: c, chunk-template: {a: b} | 'chunk-template' must be text, a number or a boolean (got object)",
      "corpus: c, embedding-model: [m]   | 'embedding-model' must be text, a number or a boolean (got array)"})
  void rejects_a_rag_text_knob_that_is_not_text(String rag, String message) {
    assertThatThrownBy(() -> MappingLoader.load("id: m\nversion: 1\ntarget:\n  rag: { " + rag + " }\n"))
      .isInstanceOf(MappingValidationException.class)
      .hasMessage("target.rag: " + message);
  }

  /** A bare key is absent, as everywhere else in a manifest: not the word "null". */
  @Test
  void a_bare_rag_knob_reads_as_absent() {
    MappingTarget t = MappingLoader.load("""
      id: m
      version: 1
      target:
        rag:
          corpus: c
          chunk-template:
          embedding-model:
      """).targets();
    assertThat(t.rag().chunkTemplate()).isEmpty();
    assertThat(t.rag().embeddingModel()).isNull();
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

  /**
   * #1472: the table, jpa and index knobs are read typed. asText() read a list or a map as "" (an index over no
   * column, an unknown enum constant thrown raw) and a null as the word "null" (a table named null).
   */
  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
      "table: { schema: s, name: [n] }                     | target.table: 'name' must be text, a number or a boolean (got array)",
      "table: { schema: { a: b }, name: n }                | target.table: 'schema' must be text, a number or a boolean (got object)",
      "table: { name: n, on-conflict: [update] }           | target.table: 'on-conflict' must be text, a number or a boolean (got array)",
      "table: { name: n, on-conflict: merge }              | target.table: 'on-conflict' must be skip, update or error",
      "table: { name: n, indexes: { columns: [a] } }       | target.table: 'indexes' must be a list of { columns: [...], unique } (got object)",
      "table: { name: n, indexes: [email] }                | target.table: 'indexes[0]' must be { columns: [...], unique } (got string)",
      "table: { name: n, indexes: [{ columns: email }] }   | target.table.indexes[0]: 'columns' must be a list of text, numbers or booleans (got string)",
      "table: { name: n, indexes: [{ columns: [[a]] }] }   | target.table.indexes[0]: 'columns[0]' must be text, a number or a boolean (got array)",
      "table: { name: n, indexes: [{ unique: true }] }     | target.table.indexes[0]: 'columns' must be a list of text, numbers or booleans (got missing)",
      "table: { name: n, indexes: [{ columns: [a, ''] }] }  | target.table.indexes[0]: 'columns[1]' must name a column (got blank text)",
      "table: { name: n, indexes: [{ columns: ['  '] }] }   | target.table.indexes[0]: 'columns[0]' must name a column (got blank text)",
      "jpa: { entity: [pkg.Entity] }                       | target.jpa: 'entity' must be text, a number or a boolean (got array)"})
  void rejects_a_target_knob_of_the_wrong_shape(String target, String message) {
    assertThatThrownBy(() -> MappingLoader.load("id: m\nversion: 1\ntarget:\n  " + target + "\n"))
      .isInstanceOf(MappingValidationException.class)
      .hasMessage(message);
  }

  /** A bare, empty or blank `name:` names no table — refused as a missing identifier, never a table named "null". */
  @ParameterizedTest(name = "name:{0}")
  @ValueSource(strings = {"", " \"\"", " \"   \""})
  void an_absent_or_blank_table_name_is_refused(String name) {
    assertThatThrownBy(() -> MappingLoader.load(
        "id: m\nversion: 1\ntarget:\n  table:\n    schema: s\n    name:" + name + "\n"))
      .isInstanceOf(MappingValidationException.class)
      .hasMessageStartingWith("target.table.name must be a plain identifier");
  }

  /** An index over no column is no index: refused like any other empty list. */
  @Test
  void an_index_over_no_column_is_refused() {
    assertThatThrownBy(() -> MappingLoader.load(
        "id: m\nversion: 1\ntarget:\n  table: { name: n, indexes: [{ columns: [] }] }\n"))
      .isInstanceOf(MappingValidationException.class)
      .hasMessage("target.table.indexes[0]: 'columns' must list at least one value (got an empty list)");
  }

  /** #1486: unique and auto-schema are YAML booleans; "yes", a list or a map used to read as the default, in silence. */
  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
      "table: { name: n, auto-schema: 'yes' }                     | target.table: 'auto-schema' must be true or false (got string)",
      "table: { name: n, auto-schema: [false] }                   | target.table: 'auto-schema' must be true or false (got array)",
      "table: { name: n, auto-schema: 'false' }                   | target.table: 'auto-schema' must be true or false (got string)",
      "table: { name: n, auto-schema: { a: b } }                  | target.table: 'auto-schema' must be true or false (got object)",
      "table: { name: n, indexes: [{ columns: [a], unique: { a: b } }] } | target.table.indexes[0]: 'unique' must be true or false (got object)",
      "table: { name: n, indexes: [{ columns: [a], unique: 'yes' }] } | target.table.indexes[0]: 'unique' must be true or false (got string)",
      "table: { name: n, indexes: [{ columns: [a], unique: [true] }] } | target.table.indexes[0]: 'unique' must be true or false (got array)"})
  void rejects_a_target_boolean_that_is_not_a_boolean(String target, String message) {
    assertThatThrownBy(() -> MappingLoader.load("id: m\nversion: 1\ntarget:\n  " + target + "\n"))
      .isInstanceOf(MappingValidationException.class)
      .hasMessage(message);
  }

  /** A bare boolean is absent: auto-schema stays on, unique stays off. */
  @Test
  void a_bare_target_boolean_is_its_default() {
    MappingTarget.Table t = MappingLoader.load("""
      id: m
      version: 1
      target:
        table:
          name: n
          auto-schema:
          indexes:
            - columns: [a]
              unique:
      """).targets().table();
    assertThat(t.autoSchema()).isTrue();
    assertThat(t.indexes().get(0).unique()).isFalse();
    assertThat(MappingLoader.load("id: m\nversion: 1\ntarget:\n  table: { name: n, auto-schema: false }\n")
        .targets().table().autoSchema()).isFalse();
  }

  /** on-conflict keeps its case-insensitive words and its default; a bare one is the default, as before. */
  @Test
  void on_conflict_words_and_default() {
    assertThat(MappingLoader.load("id: m\nversion: 1\ntarget:\n  table: { name: n, on-conflict: Update }\n")
        .targets().table().onConflict()).isEqualTo(MappingTarget.OnConflict.UPDATE);
    assertThat(MappingLoader.load("id: m\nversion: 1\ntarget:\n  table: { name: n, on-conflict: }\n")
        .targets().table().onConflict()).isEqualTo(MappingTarget.OnConflict.SKIP);
    assertThat(MappingLoader.load("id: m\nversion: 1\ntarget:\n  table: { name: n }\n")
        .targets().table().onConflict()).isEqualTo(MappingTarget.OnConflict.SKIP);
  }
}
