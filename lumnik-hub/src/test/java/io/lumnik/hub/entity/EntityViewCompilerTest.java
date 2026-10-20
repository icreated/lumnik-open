package io.lumnik.hub.entity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityViewCompilerTest {

  @Test
  void build_statements_fails_fast_on_a_null_source_map() {
    // The parser rejects this, but the compiler must not silently emit a branch with no
    // projections/FROM if handed a programmatic/invalid manifest — it must fail fast.
    EntityManifest m = new EntityManifest("Entity", "Customer", List.of("customer_code"),
        List.of(new EntityManifest.Source("t_x", null)));
    assertThatThrownBy(() -> new EntityViewCompiler().buildStatements(m, Set.of()))
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
    assertThatThrownBy(() -> new EntityViewCompiler().buildStatements(m, Set.of()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void array_agg_and_jsonb_agg_have_a_deterministic_secondary_sort_key() {
    // A secondary tie-break on the value itself keeps the representative and the ledger deterministic
    // even when a source has multiple rows for the same match key (ties on _ord are otherwise unordered).
    String create = new EntityViewCompiler().buildStatements(twoSources(), Set.of()).get(1);
    assertThat(create).contains("array_agg(\"name\" ORDER BY _ord, \"name\")");
    assertThat(create).contains("jsonb_agg(jsonb_build_object('src', _src, 'value', \"name\") ORDER BY _ord, \"name\")");
  }

  @Test
  void build_statements_fails_fast_on_empty_match() {
    EntityManifest m = new EntityManifest("Entity", "Customer", List.of(),
        List.of(new EntityManifest.Source("t_x", new LinkedHashMap<>(Map.of("a", "b")))));
    assertThatThrownBy(() -> new EntityViewCompiler().buildStatements(m, Set.of()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void build_statements_fails_fast_on_empty_sources() {
    EntityManifest m = new EntityManifest("Entity", "Customer", List.of("customer_code"), List.of());
    assertThatThrownBy(() -> new EntityViewCompiler().buildStatements(m, Set.of()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void build_statements_fails_fast_on_a_null_source_entry() {
    EntityManifest m = new EntityManifest("Entity", "Customer", List.of("customer_code"),
        Collections.singletonList((EntityManifest.Source) null));
    assertThatThrownBy(() -> new EntityViewCompiler().buildStatements(m, Set.of()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void build_statements_fails_fast_on_a_blank_table() {
    EntityManifest m = new EntityManifest("Entity", "Customer", List.of("customer_code"),
        List.of(new EntityManifest.Source("  ", new LinkedHashMap<>(Map.of("c", "customer_code")))));
    assertThatThrownBy(() -> new EntityViewCompiler().buildStatements(m, Set.of()))
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
    assertThatThrownBy(() -> new EntityViewCompiler().buildStatements(m, Set.of()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void build_statements_fails_fast_on_a_blank_match_element() {
    EntityManifest m = new EntityManifest("Entity", "Customer", List.of("customer_code", "  "),
        List.of(new EntityManifest.Source("t_x", new LinkedHashMap<>(Map.of("c", "customer_code")))));
    assertThatThrownBy(() -> new EntityViewCompiler().buildStatements(m, Set.of()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void build_statements_fails_fast_on_a_null_name() {
    EntityManifest m = new EntityManifest("Entity", null, List.of("customer_code"),
        List.of(new EntityManifest.Source("t_x", new LinkedHashMap<>(Map.of("c", "customer_code")))));
    assertThatThrownBy(() -> new EntityViewCompiler().buildStatements(m, Set.of()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void form_b_drop_create_grant() {
    List<String> sql = new EntityViewCompiler().buildStatements(twoSources(), Set.of());
    assertThat(sql).hasSize(4);
    assertThat(sql.get(0)).isEqualTo("DROP VIEW IF EXISTS connector.\"v_customer\"");
    assertThat(sql.get(2)).isEqualTo("GRANT SELECT ON connector.\"v_customer\" TO lumnik_app");
  }

  @Test
  void form_b_create_statement_has_the_fusion_shape() {
    String create = new EntityViewCompiler().buildStatements(twoSources(), Set.of()).get(1);
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
    String all = String.join("\n", new EntityViewCompiler().buildStatements(m, Set.of()));
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

    List<String> sql = new EntityViewCompiler().buildStatements(m, Set.of());
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
    String create = new EntityViewCompiler().buildStatements(m, Set.of()).get(1);
    assertThat(create).contains("\"weird\"\"col\""); // doubled inner quote
  }

  // #618 — resolution: projection over the crosswalk.
  // ---------------------------------------------------------------------------------------------

  private static EntityManifest resolvedTwoSources() {
    Map<String, String> erp = new LinkedHashMap<>();
    erp.put("cust_id", "customer_id");
    erp.put("cust_name", "name");
    Map<String, String> crm = new LinkedHashMap<>();
    crm.put("contact_ref", "contact_id");
    crm.put("email_addr", "email");
    EntityManifest.Resolution resolution = new EntityManifest.Resolution(
        Map.of("t_erp_customers", "customer_id", "t_crm_contacts", "contact_id"),
        List.of(new EntityManifest.CascadeRule("erp-key",
            new EntityManifest.Exact(List.of("customer_id"), List.of()), null)));
    return new EntityManifest("Entity", "Customer", null, null,
        List.of(new EntityManifest.Source("t_erp_customers", erp),
                new EntityManifest.Source("t_crm_contacts", crm)),
        List.of(), null, Map.of(), resolution);
  }

  @Test
  void build_statements_fails_fast_when_neither_match_nor_resolution_is_declared() {
    EntityManifest m = new EntityManifest("Entity", "Customer", null, null,
        List.of(new EntityManifest.Source("t_x", new LinkedHashMap<>(Map.of("c", "customer_code")))),
        List.of(), null, Map.of(), null);
    assertThatThrownBy(() -> new EntityViewCompiler().buildStatements(m, Set.of()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void build_statements_fails_fast_when_a_source_is_missing_from_resolution_identity() {
    // Same "don't rely solely on the parser" posture as matchKey: a programmatic
    // manifest whose identity map skips a declared source must not silently emit a branch that
    // joins on nothing.
    EntityManifest.Resolution resolution = new EntityManifest.Resolution(Map.of(), List.of());
    EntityManifest m = new EntityManifest("Entity", "Customer", null, null,
        List.of(new EntityManifest.Source("t_x", new LinkedHashMap<>(Map.of("c", "customer_id")))),
        List.of(), null, Map.of(), resolution);
    assertThatThrownBy(() -> new EntityViewCompiler().buildStatements(m, Set.of()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void resolved_manifest_compiles_to_the_drop_create_grant_shape() {
    List<String> sql = new EntityViewCompiler().buildStatements(resolvedTwoSources(), Set.of());
    assertThat(sql).hasSize(4);
    assertThat(sql.get(0)).isEqualTo("DROP VIEW IF EXISTS connector.\"v_customer\"");
    assertThat(sql.get(2)).isEqualTo("GRANT SELECT ON connector.\"v_customer\" TO lumnik_app");
  }

  @Test
  void resolved_manifest_left_joins_entity_identity_on_its_own_declared_identity_column_per_source() {
    String create = new EntityViewCompiler().buildStatements(resolvedTwoSources(), Set.of()).get(1);
    assertThat(create).contains("LEFT JOIN core.entity_identity");
    assertThat(create).contains("ei.entity_type = 'customer'"); // lower-cased like the view name
    assertThat(create).contains("ei.source = 't_erp_customers'");
    assertThat(create).contains("ei.source = 't_crm_contacts'");
    // each branch joins on ITS OWN physical identity column, not the other source's.
    assertThat(create).contains("\"cust_id\"::text");
    assertThat(create).contains("\"contact_ref\"::text");
  }

  @Test
  void resolved_branch_qualifies_every_physical_column_next_to_the_crosswalk_join() {
    // #967: next to LEFT JOIN core.entity_identity ei, an unqualified physical column named like one of
    // ei's own (state, source, source_id, created_at…) is ambiguous and CREATE VIEW fails. Every
    // occurrence of every physical column — projection, _source_id, join condition, NULL filter — is s.
    String create = new EntityViewCompiler().buildStatements(resolvedTwoSources(), Set.of()).get(1);
    assertThat(create).contains("FROM connector.\"t_erp_customers\" s LEFT JOIN core.entity_identity ei");
    for (String physical : List.of("cust_id", "cust_name", "contact_ref", "email_addr")) {
      assertThat(create).as(physical + " is referenced").contains("s.\"" + physical + "\"");
      assertThat(create).as(physical + " is never referenced unqualified")
          .doesNotContainPattern("(?<!s\\.)\"" + physical + "\"");
    }
  }

  @Test
  void resolved_manifest_groups_on_the_coalesced_canonical_id_not_on_a_match_column() {
    String create = new EntityViewCompiler().buildStatements(resolvedTwoSources(), Set.of()).get(1);
    assertThat(create).doesNotContain("GROUP BY \"customer_id\"");
    assertThat(create).contains("GROUP BY coalesce(");
    assertThat(create).contains("\"_entity_id\"::text");
    assertThat(create).contains("\"_src\" || ':' || \"_source_id\"");
  }

  @Test
  void resolved_manifest_exposes_entity_id_and_resolution_columns() {
    String create = new EntityViewCompiler().buildStatements(resolvedTwoSources(), Set.of()).get(1);
    assertThat(create).contains("AS \"_entity_id\"");
    assertThat(create).contains("AS \"_resolution\"");
    assertThat(create).contains("AS \"_handle\"");
  }

  @Test
  void resolved_manifest_conflicts_ledger_still_only_flags_a_genuine_difference() {
    // No match: set carves out an "equal by construction" column in resolution mode, so every
    // canonical column — including the per-source identity ones — goes through the same
    // count(DISTINCT …) > 1 ledger mechanism as v1's non-match columns (#618 acceptance: unchanged).
    String create = new EntityViewCompiler().buildStatements(resolvedTwoSources(), Set.of()).get(1);
    assertThat(create).contains("jsonb_strip_nulls(jsonb_build_object(");
    assertThat(create).contains("count(DISTINCT \"customer_id\") > 1");
    assertThat(create).contains("count(DISTINCT \"email\") > 1");
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
