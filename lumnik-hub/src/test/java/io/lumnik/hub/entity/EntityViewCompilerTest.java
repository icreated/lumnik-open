package io.lumnik.hub.entity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityViewCompilerTest {

  @Test
  void build_statements_fails_fast_on_a_null_source_map() {
    // The parser rejects this, but the compiler must not silently emit a branch with no
    // projections/FROM if handed a programmatic/invalid manifest — it must fail fast.
    EntityManifest m = new EntityManifest("Entity", "Customer", List.of("customer_code"),
        List.of(new EntityManifest.Source("t_x", null)));
    assertThatThrownBy(() -> new EntityViewCompiler().buildStatements(m))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void build_statements_fails_fast_when_a_source_omits_the_match_column() {
    // Source maps 'name' but NOT the match column 'customer_code'. Silently skipping the
    // WHERE filter would fold all rows into a NULL-key group — fail fast instead.
    Map<String, String> map = new LinkedHashMap<>();
    map.put("other", "name");
    EntityManifest m = new EntityManifest("Entity", "Customer", List.of("customer_code"),
        List.of(new EntityManifest.Source("t_x", map)));
    assertThatThrownBy(() -> new EntityViewCompiler().buildStatements(m))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void array_agg_and_jsonb_agg_have_a_deterministic_secondary_sort_key() {
    // A secondary tie-break on the value itself keeps the representative and the ledger deterministic
    // even when a source has multiple rows for the same match key (ties on _ord are otherwise unordered).
    String create = new EntityViewCompiler().buildStatements(twoSources()).get(1);
    assertThat(create).contains("array_agg(\"name\" ORDER BY _ord, \"name\")");
    assertThat(create).contains("jsonb_agg(jsonb_build_object('src', _src, 'value', \"name\") ORDER BY _ord, \"name\")");
  }

  @Test
  void build_statements_fails_fast_on_empty_match() {
    EntityManifest m = new EntityManifest("Entity", "Customer", List.of(),
        List.of(new EntityManifest.Source("t_x", new LinkedHashMap<>(Map.of("a", "b")))));
    assertThatThrownBy(() -> new EntityViewCompiler().buildStatements(m))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void build_statements_fails_fast_on_empty_sources() {
    EntityManifest m = new EntityManifest("Entity", "Customer", List.of("customer_code"), List.of());
    assertThatThrownBy(() -> new EntityViewCompiler().buildStatements(m))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void build_statements_fails_fast_on_a_null_source_entry() {
    EntityManifest m = new EntityManifest("Entity", "Customer", List.of("customer_code"),
        java.util.Collections.singletonList((EntityManifest.Source) null));
    assertThatThrownBy(() -> new EntityViewCompiler().buildStatements(m))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void build_statements_fails_fast_on_a_blank_table() {
    EntityManifest m = new EntityManifest("Entity", "Customer", List.of("customer_code"),
        List.of(new EntityManifest.Source("  ", new LinkedHashMap<>(Map.of("c", "customer_code")))));
    assertThatThrownBy(() -> new EntityViewCompiler().buildStatements(m))
        .isInstanceOf(IllegalStateException.class);
  }

  private static EntityManifest twoSources() {
    Map<String, String> erp = new LinkedHashMap<>();
    erp.put("cust_id", "customer_code");
    erp.put("cust_name", "name");
    Map<String, String> csv = new LinkedHashMap<>();
    csv.put("code", "customer_code");
    csv.put("email", "email");
    csv.put("ville", "city");
    return new EntityManifest("Entity", "Customer", List.of("customer_code"),
        List.of(new EntityManifest.Source("t_cust_erp", erp),
                new EntityManifest.Source("t_cust_csv", csv)));
  }

  @Test
  void build_statements_fails_fast_on_a_null_match_element() {
    // List.of() rejects null; use ArrayList to construct a list with a null element.
    List<String> match = new ArrayList<>();
    match.add("customer_code");
    match.add(null);
    EntityManifest m = new EntityManifest("Entity", "Customer", match,
        List.of(new EntityManifest.Source("t_x", new LinkedHashMap<>(Map.of("c", "customer_code")))));
    assertThatThrownBy(() -> new EntityViewCompiler().buildStatements(m))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void build_statements_fails_fast_on_a_blank_match_element() {
    EntityManifest m = new EntityManifest("Entity", "Customer", List.of("customer_code", "  "),
        List.of(new EntityManifest.Source("t_x", new LinkedHashMap<>(Map.of("c", "customer_code")))));
    assertThatThrownBy(() -> new EntityViewCompiler().buildStatements(m))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void build_statements_fails_fast_on_a_null_name() {
    EntityManifest m = new EntityManifest("Entity", null, List.of("customer_code"),
        List.of(new EntityManifest.Source("t_x", new LinkedHashMap<>(Map.of("c", "customer_code")))));
    assertThatThrownBy(() -> new EntityViewCompiler().buildStatements(m))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void form_b_drop_create_grant() {
    List<String> sql = new EntityViewCompiler().buildStatements(twoSources());
    assertThat(sql).hasSize(4);
    assertThat(sql.get(0)).isEqualTo("DROP VIEW IF EXISTS connector.\"v_customer\"");
    assertThat(sql.get(2)).isEqualTo("GRANT SELECT ON connector.\"v_customer\" TO lumnik_app");
  }

  @Test
  void form_b_create_statement_has_the_fusion_shape() {
    String create = new EntityViewCompiler().buildStatements(twoSources()).get(1);
    assertThat(create).contains("CREATE VIEW connector.\"v_customer\" WITH (security_invoker = true)");
    assertThat(create).contains("WITH unified AS");
    assertThat(create).containsOnlyOnce("UNION ALL");
    assertThat(create).contains("0::int AS _ord").contains("'t_cust_erp'::text AS _src");
    assertThat(create).contains("1::int").contains("'t_cust_csv'::text");
    assertThat(create).contains("\"cust_id\"::text AS \"customer_code\"");
    assertThat(create).contains("NULL::text AS \"email\"");
    assertThat(create).contains("\"cust_id\" IS NOT NULL");  // erp branch filters its physical match col
    assertThat(create).contains("\"code\" IS NOT NULL");     // csv branch filters its physical match col
    assertThat(create).contains("(array_agg(\"name\" ORDER BY _ord, \"name\") FILTER (WHERE \"name\" IS NOT NULL))[1] AS \"name\"");
    assertThat(create).contains("count(DISTINCT \"name\") > 1");
    assertThat(create).contains("jsonb_strip_nulls(jsonb_build_object(");
    assertThat(create).doesNotContain("count(DISTINCT \"customer_code\")");
    assertThat(create).contains("GROUP BY \"customer_code\"");
  }

  @Test
  void grants_select_to_readonly_for_ask_to_sql() {
    EntityManifest m = new EntityManifest("Entity", "Customer",
        List.of("customer_code"),
        List.of(new EntityManifest.Source("t_a", Map.of("code", "customer_code"))));
    String all = String.join("\n", new EntityViewCompiler().buildStatements(m));
    assertThat(all).contains("GRANT SELECT ON connector.\"v_customer\" TO lumnik_readonly");
  }

  @Test
  void readonly_grant_dollar_tag_cannot_be_closed_by_the_entity_name() {
    // EntityManifestParser normally refuses a non-[A-Za-z0-9_] name before this is ever reached
    // (requireKindAndName), but buildStatements() must not rely solely on that upstream gate —
    // same "don't trust the caller" principle already applied to RlsDdl/DynamicTableWriter/etc.
    // A name containing "$$" must not close a bare-$$-tagged DO block early and let extra SQL run
    // as the BYPASSRLS admin role.
    EntityManifest m = new EntityManifest("Entity", "cust$$evil", List.of("customer_code"),
        List.of(new EntityManifest.Source("t_a", Map.of("code", "customer_code"))));

    List<String> sql = new EntityViewCompiler().buildStatements(m);
    String grantBlock = sql.get(3);

    assertThat(grantBlock).doesNotContain("DO $$ BEGIN");
    // The entity name's own "$$" must not forge a second matching pair of the chosen tag —
    // it appears exactly twice (open + close), same proof shape as RlsDdlInjectionTest.
    String tag = grantBlock.substring(grantBlock.indexOf("DO ") + 3, grantBlock.indexOf(" BEGIN"));
    assertThat(tag).isNotEqualTo("$$");
    assertThat(countOccurrences(grantBlock, tag)).isEqualTo(2);
  }

  private static int countOccurrences(String haystack, String needle) {
    int count = 0;
    int from = 0;
    int idx;
    while ((idx = haystack.indexOf(needle, from)) >= 0) {
      count++;
      from = idx + needle.length();
    }
    return count;
  }

  @Test
  void quote_ident_doubles_embedded_quotes() {
    // injection-safety: a column named with a double-quote must be escaped by doubling
    Map<String, String> erp = new LinkedHashMap<>();
    erp.put("customer_code", "customer_code");
    erp.put("weird\"col", "weird\"col");
    EntityManifest m = new EntityManifest("Entity", "Customer", List.of("customer_code"),
        List.of(new EntityManifest.Source("t_cust_erp", erp)));
    String create = new EntityViewCompiler().buildStatements(m).get(1);
    assertThat(create).contains("\"weird\"\"col\""); // doubled inner quote
  }

  // The 42P01 translation must fire ONLY for the manifest's own source tables: a
  // missing internal relation (entity_manifest — a migration fault) dressed as a
  // "run your connectors" 409 would be a lie worse than the 500.
  @Test
  void missing_relation_translates_only_for_declared_sources() {
    EntityManifest m = new EntityManifest("Entity", "Customer", List.of("id"),
        List.of(new EntityManifest.Source("t_cust_csv", Map.of("ROW_ID", "id"))));

    assertThat(EntityViewCompiler.namesASourceTable(m,
        "relation \"connector.t_cust_csv\" does not exist")).isTrue();
    assertThat(EntityViewCompiler.namesASourceTable(m,
        "relation \"connector.entity_manifest\" does not exist")).isFalse();
    assertThat(EntityViewCompiler.namesASourceTable(m,
        "some other 42P01 message with no relation quoted")).isFalse();
  }
}
