package io.lumnik.hub.entity;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lot #614 of the #613 entity-resolution arc: {@code resolution:} vocabulary and manifest
 * validation. No database, no engine — this class exercises {@link EntityManifestParser} only.
 * See {@code docs/superpowers/specs/2026-09-20-entity-resolution-design.md} §4 for the shape.
 */
class EntityResolutionManifestParserTest {

  private final EntityManifestParser parser = new EntityManifestParser();

  private static final String RESOLUTION_YAML = """
      kind: Entity
      name: Customer
      resolution:
        identity:
          t_erp_customers: customer_id
          t_crm_contacts: contact_id
        cascade:
          - id: erp-key
            exact: { fields: [customer_id] }
          - id: email
            exact:
              fields: [email]
              normalize: [clean, lower]
          - id: es-candidates
            propose:
              provider: elasticsearch
              acceptAbove: 0.95
              reviewAbove: 0.80
      sources:
        - table: t_erp_customers
          map:
            CUST_ID: customer_id
            EMAIL: email
        - table: t_crm_contacts
          map:
            CONTACT_ID: contact_id
            MAIL: email
      """;

  /** #1379 review: a resolution entity has no match: a column's prefer is checked without one. */
  @Test
  void a_resolution_entity_may_prefer_a_source_for_a_column() {
    EntityManifest m = parser.parse(RESOLUTION_YAML + "columns:\n  email:\n    prefer: t_crm_contacts\n");
    assertThat(m.columns().get("email").prefer()).isEqualTo("t_crm_contacts");
  }

  @Test
  void parses_a_resolution_manifest() {
    EntityManifest m = parser.parse(RESOLUTION_YAML);
    assertThat(m.match()).isNull();
    assertThat(m.resolution()).isNotNull();
    assertThat(m.resolution().identity()).containsExactly(
        Map.entry("t_erp_customers", "customer_id"),
        Map.entry("t_crm_contacts", "contact_id"));
    assertThat(m.resolution().cascade()).hasSize(3);
    assertThat(m.resolution().cascade().get(0).id()).isEqualTo("erp-key");
    assertThat(m.resolution().cascade().get(0).exact().fields()).containsExactly("customer_id");
    assertThat(m.resolution().cascade().get(1).exact().normalize()).containsExactly("clean", "lower");
    assertThat(m.resolution().cascade().get(2).propose().provider()).isEqualTo("elasticsearch");
    assertThat(m.resolution().cascade().get(2).propose().acceptAbove()).isEqualTo(0.95);
    assertThat(m.resolution().cascade().get(2).propose().reviewAbove()).isEqualTo(0.80);
  }

  @Test
  void rejects_match_and_resolution_together() {
    String bad = RESOLUTION_YAML.replace("kind: Entity\nname: Customer",
        "kind: Entity\nname: Customer\nmatch: [customer_id]");
    assertThatThrownBy(() -> parser.parse(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("match").hasMessageContaining("resolution");
  }

  @Test
  void existing_v1_manifest_without_resolution_is_unaffected() {
    EntityManifest m = parser.parse("""
        kind: Entity
        name: Customer
        match: [id]
        sources:
          - table: t_cust_src
            map:
              ROW_ID: id
        """);
    assertThat(m.resolution()).isNull();
    assertThat(m.match()).containsExactly("id");
  }

  @Test
  void rejects_resolution_missing_identity_for_a_declared_source() {
    String bad = RESOLUTION_YAML.replace("    t_crm_contacts: contact_id\n", "");
    assertThatThrownBy(() -> parser.parse(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("identity");
  }

  @Test
  void rejects_resolution_identity_naming_an_undeclared_source() {
    String bad = RESOLUTION_YAML.replace(
        "    t_crm_contacts: contact_id\n",
        "    t_crm_contacts: contact_id\n    t_ghost: id\n");
    assertThatThrownBy(() -> parser.parse(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("identity").hasMessageContaining("t_ghost");
  }

  @Test
  void rejects_resolution_identity_column_not_mapped_by_its_own_source() {
    String bad = RESOLUTION_YAML.replace(
        "    t_erp_customers: customer_id", "    t_erp_customers: not_mapped");
    assertThatThrownBy(() -> parser.parse(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("t_erp_customers").hasMessageContaining("not_mapped");
  }

  @Test
  void rejects_empty_cascade() {
    String bad = RESOLUTION_YAML.replaceAll("(?s)  cascade:.*?(?=\\n *sources:)", "  cascade: []\n");
    assertThatThrownBy(() -> parser.parse(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cascade");
  }

  @Test
  void rejects_cascade_rule_missing_id() {
    String bad = RESOLUTION_YAML.replace(
        "    - id: erp-key\n      exact: { fields: [customer_id] }",
        "    - exact: { fields: [customer_id] }");
    assertThatThrownBy(() -> parser.parse(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("id");
  }

  @Test
  void rejects_duplicate_cascade_ids_case_insensitively() {
    String bad = RESOLUTION_YAML.replace("id: email", "id: ERP-KEY");
    assertThatThrownBy(() -> parser.parse(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate");
  }

  @Test
  void rejects_a_cascade_rule_naming_neither_exact_nor_propose() {
    String bad = RESOLUTION_YAML.replace(
        "    - id: erp-key\n      exact: { fields: [customer_id] }",
        "    - id: erp-key\n      label: nope");
    assertThatThrownBy(() -> parser.parse(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("erp-key");
  }

  @Test
  void rejects_a_cascade_rule_naming_both_exact_and_propose() {
    String bad = RESOLUTION_YAML.replace(
        "    - id: erp-key\n      exact: { fields: [customer_id] }",
        "    - id: erp-key\n      exact: { fields: [customer_id] }\n"
            + "      propose: { provider: elasticsearch, acceptAbove: 0.9, reviewAbove: 0.5 }");
    assertThatThrownBy(() -> parser.parse(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("erp-key");
  }

  @Test
  void rejects_exact_with_no_fields() {
    String bad = RESOLUTION_YAML.replace("exact: { fields: [customer_id] }", "exact: { fields: [] }");
    assertThatThrownBy(() -> parser.parse(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fields");
  }

  @Test
  void rejects_exact_field_that_is_not_a_declared_canonical_column() {
    String bad = RESOLUTION_YAML.replace("fields: [customer_id]", "fields: [nonexistent]");
    assertThatThrownBy(() -> parser.parse(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nonexistent");
  }

  @Test
  void rejects_exact_shorthand_form() {
    // exact: has exactly one shape, the object form — a bare list is refused, not silently coerced.
    String bad = RESOLUTION_YAML.replace("exact: { fields: [customer_id] }", "exact: [customer_id]");
    assertThatThrownBy(() -> parser.parse(bad)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejects_unknown_normalize_id() {
    String bad = RESOLUTION_YAML.replace("normalize: [clean, lower]", "normalize: [clean, trim]");
    assertThatThrownBy(() -> parser.parse(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("trim");
  }

  @Test
  void rejects_excluded_normalize_id() {
    // 'mask' collapses every value onto a mass false merge — excluded, not merely unlisted.
    String bad = RESOLUTION_YAML.replace("normalize: [clean, lower]", "normalize: [mask]");
    assertThatThrownBy(() -> parser.parse(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mask");
  }

  @Test
  void accepts_a_cascade_rule_with_no_normalize() {
    EntityManifest m = parser.parse(RESOLUTION_YAML);
    assertThat(m.resolution().cascade().get(0).exact().normalize()).isEmpty();
  }

  @Test
  void rejects_propose_without_provider() {
    String bad = RESOLUTION_YAML.replace(
        "provider: elasticsearch\n        acceptAbove: 0.95\n        reviewAbove: 0.80",
        "acceptAbove: 0.95\n        reviewAbove: 0.80");
    assertThatThrownBy(() -> parser.parse(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("provider");
  }

  @Test
  void rejects_accept_above_not_greater_than_review_above() {
    String bad = RESOLUTION_YAML.replace("acceptAbove: 0.95", "acceptAbove: 0.80");
    assertThatThrownBy(() -> parser.parse(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("acceptAbove").hasMessageContaining("reviewAbove");
  }

  @Test
  void rejects_threshold_out_of_unit_range() {
    String bad = RESOLUTION_YAML.replace("acceptAbove: 0.95", "acceptAbove: 1.5");
    assertThatThrownBy(() -> parser.parse(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("acceptAbove");
  }

  @Test
  void does_not_refuse_propose_naming_an_unavailable_provider_at_apply_time() {
    // Not an apply-time refusal per the design — surfaces later as unresolved_provider.
    String withUnknownProvider = RESOLUTION_YAML.replace("provider: elasticsearch", "provider: some-future-provider");
    EntityManifest m = parser.parse(withUnknownProvider);
    assertThat(m.resolution().cascade().get(2).propose().provider()).isEqualTo("some-future-provider");
  }

  @Test
  void rejects_composite_identity_for_a_source() {
    // v1 accepts only a simple (single-column) identity per source.
    String bad = RESOLUTION_YAML.replace(
        "t_erp_customers: customer_id", "t_erp_customers: [customer_id, email]");
    assertThatThrownBy(() -> parser.parse(bad)).isInstanceOf(IllegalArgumentException.class);
  }
}
