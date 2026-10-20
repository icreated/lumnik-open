package io.lumnik.hub.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** #965: the disputed-rows probe relation — what it reads, when it declines, and that it shares the view's fusion rule. */
class EntityViewCompilerProbeTest {

  private final EntityManifestParser parser = new EntityManifestParser();
  private final EntityViewCompiler compiler = new EntityViewCompiler();

  private static final String TWO_SOURCES = """
      kind: Entity
      name: Cust
      match: [code]
      sources:
        - table: t_erp
          map: { code: code, city: city, segment: segment }
        - table: t_crm
          map: { customer: code, town: city }
      """;

  private Optional<EntityViewCompiler.ProbeRelation> relation(String yaml, String probed, String... others) {
    return compiler.probeRelation(parser.parse(yaml), EntityViewCompiler.Sources.of(Set.of()), probed, List.of(others));
  }

  @Test
  void a_probe_reads_only_the_records_a_source_holds_the_value_of_and_the_sources_disagree_on() {
    EntityViewCompiler.ProbeRelation r = relation(TWO_SOURCES, "city").orElseThrow();
    assertThat(r.sql())
        .contains("WITH h AS (SELECT \"code\"::text AS \"_k0\" FROM connector.\"t_erp\" WHERE \"city\"::text = ANY(?)")
        .contains("UNION\nSELECT \"customer\"::text AS \"_k0\" FROM connector.\"t_crm\" WHERE \"town\"::text = ANY(?)")
        .contains("HAVING min(v) <> max(v)")
        .contains("IN (SELECT \"_k0\" FROM k)");
    assertThat(r.paramColumns()).containsExactly("city", "city");
  }

  @Test
  void a_filter_on_the_match_key_narrows_the_candidates_and_binds_its_values_after_the_probed_ones() {
    EntityViewCompiler.ProbeRelation r = relation(TWO_SOURCES, "city", "code").orElseThrow();
    assertThat(r.sql()).contains("\"city\"::text = ANY(?) AND \"code\"::text = ANY(?)");
    assertThat(r.paramColumns()).containsExactly("city", "code", "city", "code");
  }

  @Test
  void the_relation_carries_the_probed_and_other_filtered_columns_and_only_the_probed_ledger() {
    String sql = relation(TWO_SOURCES, "city", "segment").orElseThrow().sql();
    assertThat(sql)
        .contains("AS \"city\"").contains("AS \"segment\"")
        .contains("jsonb_build_object('city', CASE WHEN count(DISTINCT \"city\")")
        .doesNotContain("'segment', CASE");
  }

  @Test
  void the_fusion_rule_is_the_views_own_not_a_second_copy() {
    // The two renderings must contain the SAME text for a column's representative and ledger entry.
    String view = compiler.buildStatements(parser.parse(TWO_SOURCES), Set.of()).get(1);
    String probe = relation(TWO_SOURCES, "city").orElseThrow().sql();
    String representative = "(array_agg(\"city\" ORDER BY _ord, \"city\") FILTER (WHERE \"city\" IS NOT NULL))[1]";
    String ledger = "CASE WHEN count(DISTINCT \"city\") > 1 THEN jsonb_agg(jsonb_build_object('src', _src, 'value', \"city\")"
        + " ORDER BY _ord, \"city\") FILTER (WHERE \"city\" IS NOT NULL) END";
    assertThat(view).contains(representative).contains(ledger);
    assertThat(probe).contains(representative).contains(ledger);
  }

  @Test
  void a_composite_key_compares_the_whole_tuple() {
    String yaml = """
        kind: Entity
        name: Cust
        match: [code, site]
        sources:
          - table: t_erp
            map: { code: code, site: site, city: city }
          - table: t_crm
            map: { customer: code, plant: site, town: city }
        """;
    String sql = relation(yaml, "city").orElseThrow().sql();
    assertThat(sql)
        .contains("(\"code\"::text, \"site\"::text) IN (SELECT \"_k0\", \"_k1\" FROM k)")
        .contains("GROUP BY \"_k0\", \"_k1\" HAVING");
  }

  @Test
  void a_source_that_does_not_map_the_probed_column_adds_nothing_to_the_candidates() {
    String yaml = TWO_SOURCES.replace("{ customer: code, town: city }", "{ customer: code, tier: segment }");
    String sql = relation(yaml, "city").orElseThrow().sql();
    assertThat(sql).contains("FROM connector.\"t_erp\" WHERE \"city\"::text = ANY(?)").doesNotContain("\"tier\"::text = ANY");
  }

  @Test
  void no_source_mapping_the_probed_column_leaves_the_view_to_read() {
    String yaml = TWO_SOURCES.replace("segment: segment", "name: name");
    assertThat(relation(yaml, "segment")).isEmpty();
  }

  @Test
  void a_match_key_is_never_a_probed_column() {
    assertThat(relation(TWO_SOURCES, "code")).isEmpty();
  }

  @Test
  void a_resolution_entity_has_no_match_key_to_group_by() {
    var sources = List.of(new EntityManifest.Source("t_erp", new LinkedHashMap<>(Map.of("code", "code", "city", "city"))));
    var m = new EntityManifest("Entity", "Cust", null, List.of(), sources, List.of(), null, Map.of(),
        new EntityManifest.Resolution(Map.of("t_erp", "code"), List.of()), List.of());
    assertThat(compiler.probeRelation(m, EntityViewCompiler.Sources.of(Set.of()), "city", List.of())).isEmpty();
  }

  @Test
  void a_column_a_lookup_provides_is_left_to_the_view_but_a_fused_one_beside_it_is_not() {
    String yaml = EntityManifestJoinsParserTest.BASE;   // category_name comes from the lookup
    assertThat(relation(yaml, "category_name")).isEmpty();
    assertThat(relation(yaml, "product_name", "category_name")).isEmpty();
    assertThat(relation(yaml, "product_name")).isPresent();
  }
}
