package io.lumnik.hub.entity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

class EntityManifestParserTest {

  private static final String CUSTOMER_YAML = """
      kind: Entity
      name: Customer
      match: [id]
      sources:
        - table: t_cust_src
          map:
            ROW_ID: id
            NAME: name
            OU_TYPE_CD: segment
      """;

  @Test
  void parses_a_single_source_customer_manifest() {
    EntityManifest m = new EntityManifestParser().parse(CUSTOMER_YAML);
    assertThat(m.kind()).isEqualTo("Entity");
    assertThat(m.name()).isEqualTo("Customer");
    assertThat(m.match()).containsExactly("id");
    assertThat(m.sources()).hasSize(1);
    assertThat(m.sources().get(0).table()).isEqualTo("t_cust_src");
    assertThat(m.sources().get(0).map()).containsExactly(
        entry("ROW_ID", "id"), entry("NAME", "name"), entry("OU_TYPE_CD", "segment"));
    assertThat(m.canonicalColumns()).containsExactly("id", "name", "segment");
  }

  /** #1489: a column named no or on is text — YAML 1.1's boolean words are not booleans here. */
  @Test
  void yaml_1_1_boolean_words_are_column_names() {
    EntityManifest m = new EntityManifestParser().parse("""
        kind: Entity
        name: Order
        match: [no]
        sources:
          - table: t_order_src
            map: { no: no, ON: on }
        """);
    assertThat(m.match()).containsExactly("no");
    assertThat(m.sources().get(0).map()).containsExactly(entry("no", "no"), entry("ON", "on"));
  }

  @Test
  void parses_column_metadata_and_table_description() {
    EntityManifest m = new EntityManifestParser().parse("""
        kind: Entity
        name: Customer
        description: "A customer."
        match: [customer_code]
        columns:
          segment:
            description: "Commercial segment."
            values: [GOLD, SILVER, BRONZE]
          city:
            example: "Lyon"
        sources:
          - table: t_a
            map: { code: customer_code, seg: segment, ville: city }
        """);
    assertThat(m.description()).isEqualTo("A customer.");
    assertThat(m.columns()).containsKeys("segment", "city");
    assertThat(m.columns().get("segment").values()).containsExactly("GOLD", "SILVER", "BRONZE");
    assertThat(m.columns().get("city").example()).isEqualTo("Lyon");
  }

  @Test
  void parses_a_per_column_label() {
    EntityManifest m = new EntityManifestParser().parse("""
        kind: Entity
        name: Customer
        match: [customer_code]
        columns:
          customer_code:
            label: "Code client"
        sources:
          - table: t_a
            map: { code: customer_code }
        """);
    assertThat(m.columns().get("customer_code").label()).isEqualTo("Code client");
  }

  @Test
  void columns_absent_yields_empty_map_and_null_description() {
    EntityManifest m = new EntityManifestParser().parse(CUSTOMER_YAML);
    assertThat(m.columns()).isEmpty();
    assertThat(m.description()).isNull();
  }

  // #1379: each system is the reference for its own fields — a column names the source that goes first for it.
  private static String preferring(String column, String table) {
    return """
        kind: Entity
        name: Member
        match: [member_no]
        columns:
          %s:
            prefer: %s
        sources:
          - table: t_gestion
            map: { no: member_no, formule: plan, rue: street }
          - table: t_portail
            map: { no: member_no, formule: plan, rue: street, mail: email }
        """.formatted(column, table);
  }

  @Test
  void prefer_names_the_source_that_goes_first_for_one_column() {
    EntityManifest m = new EntityManifestParser().parse(preferring("street", "t_portail"));
    assertThat(m.columns().get("street").prefer()).isEqualTo("t_portail");
  }

  @Test
  void prefer_refuses_a_table_that_is_not_a_source() {
    assertThatThrownBy(() -> new EntityManifestParser().parse(preferring("street", "t_crm")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("column 'street' prefers 't_crm', which is not one of sources[] (t_gestion, t_portail)");
  }

  @Test
  void prefer_refuses_a_source_that_does_not_map_the_column() {
    assertThatThrownBy(() -> new EntityManifestParser().parse(preferring("email", "t_gestion")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("column 'email' prefers 't_gestion', which does not map it");
  }

  @Test
  void prefer_refuses_a_match_column() {
    assertThatThrownBy(() -> new EntityManifestParser().parse(preferring("member_no", "t_portail")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("column 'member_no' is a match column: every source holds the same value, there is nothing to prefer");
  }

  // #1449: a stage stamped by each flow progresses across sources; it is not a disagreement.
  private static String uncompared(String column) {
    return """
        kind: Entity
        name: OrderLine
        match: [line_id]
        columns:
          %s:
            conflicts: false
        sources:
          - table: t_bl
            map: { line_id: line_id, stage: stage }
          - table: t_cc
            map: { line_id: line_id, stage: stage, order_no: order_no }
        joins:
          - table: t_ca
            from: order_no
            to: order_no
            map: { po: acknowledged_po }
        """.formatted(column);
  }

  @Test
  void conflicts_false_marks_a_column_the_sources_do_not_dispute() {
    EntityManifest m = new EntityManifestParser().parse(uncompared("stage"));
    assertThat(m.columns().get("stage").conflicts()).isFalse();
  }

  @Test
  void conflicts_false_is_refused_on_a_match_column() {
    assertThatThrownBy(() -> new EntityManifestParser().parse(uncompared("line_id")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("column 'line_id' is a match column: every source holds the same value, there is nothing to compare");
  }

  @Test
  void conflicts_false_is_refused_on_a_joined_column() {
    assertThatThrownBy(() -> new EntityManifestParser().parse(uncompared("acknowledged_po")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("column 'acknowledged_po' is mapped by no source: only fused columns are compared, conflicts: false says nothing");
  }

  @Test
  void tolerates_a_column_key_with_no_metadata() {
    // `columns: { customer_code: }` deserializes to a null ColumnMeta — must not NPE; it's just a
    // declared key with no grounding (harmless).
    EntityManifest m = new EntityManifestParser().parse("""
        kind: Entity
        name: Customer
        match: [customer_code]
        columns:
          customer_code:
        sources:
          - table: t_a
            map: { code: customer_code }
        """);
    assertThat(m.columns()).containsKey("customer_code");
  }

  @Test
  void rejects_allowed_value_with_surrounding_whitespace() {
    // 'GOLD ' would never exact-match the model's 'GOLD' → a silent false-refusal trap. Fail fast.
    assertThatThrownBy(() -> new EntityManifestParser().parse("""
        kind: Entity
        name: Customer
        match: [customer_code]
        columns:
          segment:
            values: ["GOLD "]
        sources:
          - table: t_a
            map: { code: customer_code, seg: segment }
        """)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejects_columns_key_that_is_not_a_canonical_column() {
    assertThatThrownBy(() -> new EntityManifestParser().parse("""
        kind: Entity
        name: Customer
        match: [customer_code]
        columns:
          nonexistent:
            description: "oops"
        sources:
          - table: t_a
            map: { code: customer_code }
        """)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejects_name_that_needs_sql_quoting() {
    // The name becomes the unquoted SQL view identifier v_<name> advertised to the chat;
    // a dash/space/dot would make it unqueryable.
    String bad = CUSTOMER_YAML.replace("name: Customer", "name: my-customer");
    assertThatThrownBy(() -> new EntityManifestParser().parse(bad))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejects_wrong_kind() {
    String bad = CUSTOMER_YAML.replace("kind: Entity", "kind: Connector");
    assertThatThrownBy(() -> new EntityManifestParser().parse(bad))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejects_empty_sources() {
    String bad = "kind: Entity\nname: Customer\nmatch: [id]\nsources: []\n";
    assertThatThrownBy(() -> new EntityManifestParser().parse(bad))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejects_blank_name() {
    String bad = CUSTOMER_YAML.replace("name: Customer", "name: \"\"");
    assertThatThrownBy(() -> new EntityManifestParser().parse(bad))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static final String FUSED_YAML = """
      kind: Entity
      name: Customer
      match: [customer_code]
      sources:
        - table: t_cust_erp
          map:
            cust_id: customer_code
            cust_name: name
        - table: t_cust_csv
          map:
            code: customer_code
            email: email
      """;

  @Test
  void parses_a_two_source_manifest() {
    EntityManifest m = new EntityManifestParser().parse(FUSED_YAML);
    assertThat(m.sources()).hasSize(2);
    assertThat(m.canonicalColumns()).containsExactly("customer_code", "name", "email");
  }

  @Test
  void rejects_empty_match() {
    String bad = FUSED_YAML.replace("match: [customer_code]", "match: []");
    assertThatThrownBy(() -> new EntityManifestParser().parse(bad))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("match");
  }

  @Test
  void rejects_match_key_absent_from_a_source() {
    // csv maps 'code' -> customer_code; drop it so csv no longer produces the match key
    String bad = FUSED_YAML.replace("      code: customer_code\n", "");
    assertThatThrownBy(() -> new EntityManifestParser().parse(bad))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("match column");
  }

  @Test
  void rejects_duplicate_match_columns() {
    // match: [customer_code, CUSTOMER_CODE] — case-insensitive duplicate → redundant/ambiguous GROUP BY
    String bad = FUSED_YAML.replace("match: [customer_code]", "match: [customer_code, CUSTOMER_CODE]");
    assertThatThrownBy(() -> new EntityManifestParser().parse(bad))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate");
  }

  @Test
  void rejects_match_key_with_mismatched_canonical_case() {
    // The source maps to "CUSTOMER_CODE" but match is "customer_code". Quoted identifiers are
    // case-SENSITIVE in the generated SQL, so a case-insensitive coverage check would pass yet the
    // view's GROUP BY "customer_code" would not resolve against the projected "CUSTOMER_CODE".
    String bad = """
        kind: Entity
        name: Customer
        match: [customer_code]
        sources:
          - table: t_cust_erp
            map:
              cust_id: CUSTOMER_CODE
              cust_name: name
        """;
    assertThatThrownBy(() -> new EntityManifestParser().parse(bad))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("match column");
  }

  @Test
  void rejects_a_null_source_entry() {
    // A null list element must be a clean 400, not an NPE-500 on s.table().
    String bad = """
        kind: Entity
        name: Customer
        match: [customer_code]
        sources:
          - table: t_cust_erp
            map:
              cust_id: customer_code
          - null
        """;
    assertThatThrownBy(() -> new EntityManifestParser().parse(bad))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("null");
  }

  @Test
  void rejects_cross_source_canonical_case_mismatch() {
    // erp maps -> "email", csv maps -> "Email". Case-sensitive SQL would emit TWO separate columns,
    // almost always an accidental casing typo; reject it so a canonical name is one column.
    String bad = """
        kind: Entity
        name: Customer
        match: [customer_code]
        sources:
          - table: t_cust_erp
            map:
              cust_id: customer_code
              mail: email
          - table: t_cust_csv
            map:
              code: customer_code
              addr: Email
        """;
    assertThatThrownBy(() -> new EntityManifestParser().parse(bad))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("case");
  }

  @Test
  void rejects_reserved_canonical_name() {
    String bad = FUSED_YAML.replace("email: email", "email: _conflicts");
    assertThatThrownBy(() -> new EntityManifestParser().parse(bad))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reserved");
  }

  @Test
  void rejects_reserved_canonical_names_the_resolved_projection_now_emits() {
    // #618: the resolved projection exposes _entity_id/_resolution alongside every canonical
    // column, and each source branch carries its identity value under _source_id — a manifest
    // colliding with any of those would silently corrupt the view's own columns.
    for (String reserved : new String[] {"_entity_id", "_resolution", "_source_id", "_handle"}) {
      String bad = FUSED_YAML.replace("email: email", "email: " + reserved);
      assertThatThrownBy(() -> new EntityManifestParser().parse(bad))
          .as("canonical name '%s' must be reserved", reserved)
          .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reserved");
    }
  }

  @Test
  void rejects_duplicate_canonical_within_a_source() {
    String bad = FUSED_YAML.replace("cust_name: name", "cust_name: customer_code");
    assertThatThrownBy(() -> new EntityManifestParser().parse(bad))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate");
  }

  @Test
  void rejects_blank_match_element() {
    String bad = FUSED_YAML.replace("match: [customer_code]", "match: [\"\"]");
    assertThatThrownBy(() -> new EntityManifestParser().parse(bad))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("blank");
  }

  private final EntityManifestParser parser = new EntityManifestParser();

  @Test
  void parses_scopes_list() {
    EntityManifest m = parser.parse("""
        kind: Entity
        name: Customer
        match: [customer_code]
        scopes: [clients, ventes]
        sources:
          - table: t_a
            map: { code: customer_code }
        """);
    assertThat(m.scopes()).containsExactly("clients", "ventes");
  }

  @Test
  void scopes_absent_defaults_to_empty() {
    EntityManifest m = parser.parse("""
        kind: Entity
        name: Customer
        match: [customer_code]
        sources:
          - table: t_a
            map: { code: customer_code }
        """);
    assertThat(m.scopes()).isEmpty();
  }

  @Test
  void rejects_blank_scope() {
    assertThatThrownBy(() -> parser.parse("""
        kind: Entity
        name: Customer
        match: [customer_code]
        scopes: ["", clients]
        sources:
          - table: t_a
            map: { code: customer_code }
        """)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejects_non_simple_scope() {
    assertThatThrownBy(() -> parser.parse("""
        kind: Entity
        name: Customer
        match: [customer_code]
        scopes: ["a b"]
        sources:
          - table: t_a
            map: { code: customer_code }
        """)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void canonical_columns_are_the_fused_columns_then_the_joined_ones() {
    EntityManifest m = new EntityManifest("Entity", "Product", null, List.of("product_id"),
        List.of(new EntityManifest.Source("t_p", new LinkedHashMap<>(
            Map.of("pid", "product_id")))),
        List.of(), null, Map.of(), null,
        List.of(new EntityManifest.Join("t_c", "category_id", "cid",
            new LinkedHashMap<>(Map.of("cname", "category_name")))));
    assertThat(m.fusedColumns()).containsExactly("product_id");
    assertThat(m.joinedColumns()).containsExactly("category_name");
    assertThat(m.canonicalColumns()).containsExactly("product_id", "category_name");
  }

  @Test
  void a_manifest_without_joins_has_the_same_canonical_and_fused_columns() {
    EntityManifest m = new EntityManifestParser().parse(CUSTOMER_YAML);
    assertThat(m.joins()).isEmpty();
    assertThat(m.canonicalColumns()).isEqualTo(m.fusedColumns());
  }
}
