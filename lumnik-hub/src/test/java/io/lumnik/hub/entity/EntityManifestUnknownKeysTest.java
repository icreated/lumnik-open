package io.lumnik.hub.entity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The half of C3 that kind:Entity never got. View, Process and Workflow each name the keys they do
 * not honour; Entity deserialized with {@code FAIL_ON_UNKNOWN_PROPERTIES=false} and reported
 * nothing, so a key it ignored was the integrator writing something, the hub answering 200, and the
 * thing they wrote never happening.
 *
 * <p>Two of those silences cost real behaviour, which is why this is not tidiness:
 * <ul>
 *   <li>{@code scope:} written for {@code scopes:} — the record's compact constructor defaults the
 *       list to empty, and a scopeless entity is invisible to every scope-bound user by the very
 *       rule that makes it visible to everyone else;
 *   <li>{@code colums:} written for {@code columns:} — the whole grounding block disappears:
 *       labels, examples, and the {@code values} domain the DomainGuard enforces.
 * </ul>
 *
 * <p>Reporting only, exactly as the View parser is: {@code parse()} stays lenient so an entity
 * already stored can still be read. The apply door is where a manifest is refused.
 */
class EntityManifestUnknownKeysTest {

  private final EntityManifestParser parser = new EntityManifestParser();

  private static final String VALID = """
      kind: Entity
      name: Customer
      scopes: [clients]
      description: The fused customer
      match: [id]
      sources:
        - table: t_cust_src
          map:
            ROW_ID: id
            NAME: name
      columns:
        id:
          label: Identifiant
      """;

  @Test
  void a_complete_manifest_declares_nothing_unknown() {
    assertThat(parser.unknownKeys(VALID))
        .as("every key this manifest uses is honored — the inventory must not cry wolf")
        .isEmpty();
  }

  @Test
  void the_singular_scope_that_makes_an_entity_invisible_is_named() {
    assertThat(parser.unknownKeys(VALID.replace("scopes: [clients]", "scope: clients")))
        .as("this typo costs the entity every bound user, silently")
        .containsExactly("scope");
  }

  @Test
  void the_misspelled_grounding_block_is_named() {
    assertThat(parser.unknownKeys(VALID.replace("columns:", "colums:")))
        .as("this typo costs every label, example and value domain")
        .containsExactly("colums");
  }

  @Test
  void an_unknown_key_inside_a_source_is_named_with_its_index() {
    assertThat(parser.unknownKeys(VALID.replace("- table: t_cust_src", "- tabel: t_cust_src")))
        .containsExactly("sources[0].tabel");
  }

  @Test
  void a_wholly_invented_root_key_is_named() {
    assertThat(parser.unknownKeys(VALID + "totalement-inconnu: 42\n"))
        .containsExactly("totalement-inconnu");
  }

  @Test
  void the_apiVersion_envelope_a_shipped_manifest_may_carry_is_not_cried_wolf_on() {
    // Declared though parse() ignores it, for the same reason the View parser declares it: the
    // shipped manifests carry the envelope, and refusing it would cry wolf on our own demo.
    assertThat(parser.unknownKeys("apiVersion: apps.lumnik.io/v1\n" + VALID)).isEmpty();
  }

  @Test
  void a_manifest_that_does_not_parse_reports_nothing_rather_than_guessing() {
    assertThat(parser.unknownKeys("kind: Entity\n  : : broken")).isEmpty();
  }

  @Test
  void the_honored_surface_names_what_is_accepted_not_only_what_is_not() {
    assertThat(EntityManifestParser.honoredSurface())
        .contains("scopes").contains("columns").contains("sources").contains("table");
  }
}
