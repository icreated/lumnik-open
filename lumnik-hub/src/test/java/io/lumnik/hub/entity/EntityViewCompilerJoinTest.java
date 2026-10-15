package io.lumnik.hub.entity;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EntityViewCompilerJoinTest {

  private final EntityManifestParser parser = new EntityManifestParser();
  private final EntityViewCompiler compiler = new EntityViewCompiler();

  private String create(String yaml) { return compiler.buildStatements(parser.parse(yaml)).get(1); }

  @Test
  void the_fused_view_is_wrapped_not_rewritten() {
    String withoutJoins = create(EntityManifestJoinsParserTest.BASE.substring(
        0, EntityManifestJoinsParserTest.BASE.indexOf("joins:")));
    String withJoins = create(EntityManifestJoinsParserTest.BASE);
    String fusedBody = withoutJoins.substring(withoutJoins.indexOf("AS WITH unified") + 3);
    assertThat(withJoins).contains("WITH fused AS (" + fusedBody + ")");
  }

  private static final String KEY_PATTERN = "'^-?(0|[1-9][0-9]{0,37})([.][0-9]{1,38})?$'";

  /** The one rule (#864), spelled for an expression already cast to text. */
  private static String rule(String textExpr) {
    return "(CASE WHEN " + textExpr + " ~ " + KEY_PATTERN
        + " THEN trim_scale((" + textExpr + ")::numeric)::text ELSE " + textExpr + " END)";
  }

  @Test
  void the_lookup_is_grouped_by_key_and_counted() {
    String sql = create(EntityManifestJoinsParserTest.BASE);
    assertThat(sql)
        .contains("lk0 AS MATERIALIZED (SELECT " + rule("\"m_product_category_id\"::text") + " AS \"_k\", count(*) AS \"_n\"")
        .contains("FROM connector.\"t_product_categories\" WHERE \"m_product_category_id\" IS NOT NULL GROUP BY 1")
        .contains("CASE WHEN lk0.\"_n\" = 1 THEN lk0.\"category_name\" END AS \"category_name\"")
        .contains("CASE WHEN lk0.\"_n\" = 1 THEN lk0.\"category_description\" END AS \"category_description\"");
  }

  @Test
  void both_sides_of_the_join_are_compared_by_the_same_rule() {
    assertThat(create(EntityManifestJoinsParserTest.BASE))
        .contains("LEFT JOIN lk0 ON " + rule("f.\"category_id\"::text") + " = lk0.\"_k\"");
  }

  @Test
  void every_aggregate_of_a_lookup_shares_one_total_order() {
    String sql = create(EntityManifestJoinsParserTest.BASE);
    String ord = "\"name\"::text COLLATE \"C\" NULLS LAST, \"description\"::text COLLATE \"C\" NULLS LAST";
    assertThat(sql).contains("array_agg(\"name\"::text ORDER BY " + ord + ")");
    assertThat(sql).contains("array_agg(\"description\"::text ORDER BY " + ord + ")");
    assertThat(sql).contains("'value', \"name\"::text) ORDER BY " + ord + ")");
    assertThat(sql).contains("'value', \"description\"::text) ORDER BY " + ord + ")");
  }

  @Test
  void an_ambiguous_key_is_appended_to_the_ledger_after_the_fusion_ledger() {
    assertThat(create(EntityManifestJoinsParserTest.BASE)).contains(
        "f.\"_conflicts\" || (CASE WHEN lk0.\"_n\" > 1 THEN lk0.\"_amb\" ELSE '{}'::jsonb END) AS \"_conflicts\"");
  }

  @Test
  void the_view_stays_security_invoker_and_the_grants_are_unchanged() {
    var stmts = compiler.buildStatements(parser.parse(EntityManifestJoinsParserTest.BASE));
    assertThat(stmts).hasSize(4);
    assertThat(stmts.get(1)).startsWith("CREATE VIEW connector.\"v_product\" WITH (security_invoker = true) AS WITH fused AS (");
    assertThat(stmts.get(2)).startsWith("GRANT SELECT ON ");
  }

  @Test
  void an_empty_joins_list_compiles_byte_identically_to_no_joins_key() {
    String base = EntityManifestJoinsParserTest.BASE;
    String noKey = base.substring(0, base.indexOf("joins:"));
    assertThat(compiler.buildStatements(parser.parse(noKey + "joins: []\n")))
        .isEqualTo(compiler.buildStatements(parser.parse(noKey)));
  }

  // #871 follow-up: the structural guarantee — not just "plausibly" — that every join's own
  // lookup CTE is MATERIALIZED, whatever the entity's shape. lookupCte() is the ONE place the
  // keyword is written, called once per join from joinedBody()'s own loop and identically whether
  // the fused body underneath is a match: or a resolution: entity (joinedBody only branches on
  // resolution for the technical passthrough columns, never for the join/lookup mechanics) — these
  // two tests pin that claim in the generated SQL instead of leaving it an inference from reading
  // the loop.

  @Test
  void every_join_of_a_multi_join_entity_materializes_its_own_lookup() {
    EntityManifest.Join categoryJoin = new EntityManifest.Join("t_product_categories", "category_id",
        "m_product_category_id", new LinkedHashMap<>(Map.of("name", "category_name")));
    EntityManifest.Join brandJoin = new EntityManifest.Join("t_brands", "brand_id",
        "m_brand_id", new LinkedHashMap<>(Map.of("name", "brand_name")));
    Map<String, String> map = new LinkedHashMap<>();
    map.put("m_product_id", "product_id");
    map.put("m_product_category_id", "category_id");
    map.put("m_brand_id", "brand_id");
    EntityManifest m = new EntityManifest("Entity", "Product", null, List.of("product_id"),
        List.of(new EntityManifest.Source("t_products", map)),
        List.of(), null, Map.of(), null, List.of(categoryJoin, brandJoin));

    String sql = compiler.buildStatements(m).get(1);
    assertThat(sql).contains("lk0 AS MATERIALIZED (SELECT");
    assertThat(sql).contains("lk1 AS MATERIALIZED (SELECT");
  }

  @Test
  void a_resolution_entity_materializes_its_join_lookup_exactly_like_a_match_entity() {
    Map<String, String> erp = new LinkedHashMap<>();
    erp.put("cust_id", "customer_id");
    erp.put("region_ref", "region_id");
    EntityManifest.Resolution resolution = new EntityManifest.Resolution(
        Map.of("t_erp_customers", "customer_id"),
        List.of(new EntityManifest.CascadeRule("erp-key",
            new EntityManifest.Exact(List.of("customer_id"), List.of()), null)));
    EntityManifest.Join regionJoin = new EntityManifest.Join("t_regions", "region_id", "m_region_id",
        new LinkedHashMap<>(Map.of("name", "region_name")));
    EntityManifest m = new EntityManifest("Entity", "Customer", null, null,
        List.of(new EntityManifest.Source("t_erp_customers", erp)),
        List.of(), null, Map.of(), resolution, List.of(regionJoin));

    String sql = compiler.buildStatements(m).get(1);
    assertThat(sql).contains("lk0 AS MATERIALIZED (SELECT");
  }
}
