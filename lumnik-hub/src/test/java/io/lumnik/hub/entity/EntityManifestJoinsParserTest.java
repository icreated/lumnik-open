package io.lumnik.hub.entity;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityManifestJoinsParserTest {

  static final String BASE = """
      kind: Entity
      name: Product
      match: [product_id]
      sources:
        - table: t_products
          map: { m_product_id: product_id, m_product_category_id: category_id, name: product_name }
      joins:
        - table: t_product_categories
          from: category_id
          to: m_product_category_id
          map: { name: category_name, description: category_description }
      """;

  private final EntityManifestParser parser = new EntityManifestParser();

  @Test
  void the_reference_manifest_parses_and_exposes_the_joined_columns() {
    EntityManifest m = parser.parse(BASE);
    assertThat(m.joins()).hasSize(1);
    assertThat(m.joins().get(0).from()).isEqualTo("category_id");
    assertThat(m.canonicalColumns()).containsExactly(
        "product_id", "category_id", "product_name", "category_name", "category_description");
  }

  @Test
  void an_empty_joins_list_is_no_join() {
    EntityManifest m = parser.parse(BASE.substring(0, BASE.indexOf("joins:")) + "joins: []\n");
    assertThat(m.joins()).isEmpty();
  }

  static Stream<Arguments> refusals() {
    return Stream.of(
        Arguments.of("from not a fused column", "from: category_id", "from: nowhere", "joins[0].from"),
        Arguments.of("blank to", "to: m_product_category_id", "to: ''", "joins[0].to"),
        Arguments.of("blank table", "table: t_product_categories", "table: ''", "joins[0].table"),
        Arguments.of("empty map", "map: { name: category_name, description: category_description }", "map: {}", "joins[0].map"),
        Arguments.of("joined column collides with a source column (case-insensitive)",
            "name: category_name", "name: Product_Name", "collides"),
        Arguments.of("joined column is a match column", "name: category_name", "name: product_id", "identity"),
        Arguments.of("joined column is reserved", "name: category_name", "name: _conflicts", "reserved"),
        Arguments.of("joined column named _n", "name: category_name", "name: _n", "reserved"),
        Arguments.of("joined column named _k", "name: category_name", "name: _k", "reserved"),
        Arguments.of("joined column named _amb", "name: category_name", "name: _amb", "reserved"),
        Arguments.of("lookup is also a source", "table: t_product_categories", "table: t_products", "also a source"),
        Arguments.of("duplicate joined column inside one map",
            "description: category_description", "description: category_name", "duplicate"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("refusals")
  void a_structural_defect_is_refused_with_a_message_that_says_where(
      String label, String from, String to, String mustMention) {
    assertThat(BASE).contains(from);
    assertThatThrownBy(() -> parser.parse(BASE.replace(from, to)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(mustMention);
  }

  @Test
  void the_same_lookup_table_twice_is_refused() {
    String twice = BASE + """
          - table: t_product_categories
            from: category_id
            to: m_product_category_id
            map: { other: other_col }
        """;
    assertThatThrownBy(() -> parser.parse(twice))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("more than once");
  }

  private static final String RESOLUTION_WITH_JOIN = """
      kind: Entity
      name: Product
      sources:
        - table: t_products
          map: { pid: product_id, cid: category_id }
      resolution:
        identity: { t_products: product_id }
        cascade:
          - id: r1
            exact: { fields: [product_id] }
      joins:
        - table: t_c
          from: category_id
          to: cid
          map: { n: category_name }
      """;

  @Test
  void joins_together_with_resolution_is_accepted() {
    EntityManifest m = parser.parse(RESOLUTION_WITH_JOIN);
    assertThat(m.resolution()).isNotNull();
    assertThat(m.joins()).hasSize(1);
    assertThat(m.canonicalColumns()).contains("category_name");
  }

  @Test
  void a_join_on_a_resolution_manifest_still_refuses_what_a_match_manifest_refuses() {
    String yaml = RESOLUTION_WITH_JOIN.replace("from: category_id", "from: nope");
    assertThatThrownBy(() -> parser.parse(yaml))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("joins[0].from 'nope' is not a canonical column");
  }

  @Test
  void an_unknown_key_inside_a_join_is_named_by_its_path() {
    assertThat(parser.unknownKeys(BASE.replace("to: m_product_category_id", "to: m_product_category_id\n    typo: 1")))
        .containsExactly("joins[0].typo");
    assertThat(parser.unknownKeys(BASE)).isEmpty();
  }

  @Test
  void the_honored_surface_lists_joins() {
    assertThat(EntityManifestParser.honoredSurface()).contains("joins[] {from, map, table, to}");
  }
}
