package io.lumnik.hub.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code resolution:} block extends the closed-inventory envelope walk (see
 * {@link EntityManifestUnknownKeysTest}) one level into {@code cascade[]}. {@code identity:} is
 * keyed by the integrator's own source table names, so — like {@code sources[].map} — its keys
 * cannot be inventoried; only the shape one level down (root, and each cascade rule) can.
 */
class EntityResolutionUnknownKeysTest {

  private final EntityManifestParser parser = new EntityManifestParser();

  private static final String VALID = """
      kind: Entity
      name: Customer
      resolution:
        identity:
          t_a: id
        cascade:
          - id: erp-key
            exact: { fields: [id] }
      sources:
        - table: t_a
          map:
            ROW_ID: id
      """;

  @Test
  void a_complete_resolution_manifest_declares_nothing_unknown() {
    assertThat(parser.unknownKeys(VALID)).isEmpty();
  }

  @Test
  void a_misspelled_resolution_root_key_is_named() {
    assertThat(parser.unknownKeys(VALID.replace("identity:", "identityy:")))
        .containsExactly("resolution.identityy");
  }

  @Test
  void an_unknown_key_inside_a_cascade_rule_is_named_with_its_index() {
    assertThat(parser.unknownKeys(VALID.replace("id: erp-key", "id: erp-key\n      labl: x")))
        .containsExactly("resolution.cascade[0].labl");
  }

  /**
   * Copilot round 1 on PR #631: {@code exact}/{@code propose} sit one level past what {@code
   * ManifestKeys}' array-then-object walk can express in one path entry, and the manifest's own
   * mapper tolerates unknown properties (C3) — so a misspelled {@code normalise:} would otherwise
   * silently drop to {@code Exact}'s default empty list and change the rule's matching semantics
   * with no refusal at all.
   */
  @Test
  void a_misspelled_key_inside_exact_is_named_with_its_rule_index() {
    assertThat(parser.unknownKeys(
            VALID.replace("fields: [id]", "fields: [id], normalise: [clean]")))
        .containsExactly("resolution.cascade[0].exact.normalise");
  }

  @Test
  void an_unknown_key_inside_propose_is_named_with_its_rule_index() {
    String withPropose = VALID.replace("exact: { fields: [id] }",
        "propose: { provider: es, acceptAbove: 0.9, reviewAbove: 0.8, treshold: 1 }");
    assertThat(parser.unknownKeys(withPropose))
        .containsExactly("resolution.cascade[0].propose.treshold");
  }

  @Test
  void the_honored_surface_mentions_resolution() {
    assertThat(EntityManifestParser.honoredSurface()).contains("resolution");
  }
}
