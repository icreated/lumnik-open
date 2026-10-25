package io.lumnik.hub.entity.resolution;

import io.lumnik.hub.connector.mapping.hooks.HookCatalog;
import io.lumnik.hub.connector.mapping.hooks.HookDescriptor;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Anchored on {@link HookCatalog}, the registry production resolves through — a hook added
 * tomorrow must be explicitly admitted or excluded here, not slip in silently. Same pattern as
 * {@code HookRefusalInventoryTest}. The exact membership is provisional (design doc §5): #616
 * establishes it for real with a property test per candidate hook; this pins the mechanism plus
 * today's inventory so a new hook cannot go unclassified.
 */
@QuarkusTest
class NormalizerAllowlistInventoryTest {

  @Inject HookCatalog catalog;

  @Test
  void every_cell_hook_is_admitted_or_explicitly_excluded() {
    // A test-only hook (test-carry, #1452) sits in the catalogue only while tests run: production has nothing to say.
    List<String> cells = catalog.allCellHooks().stream().map(HookDescriptor::name)
        .filter(name -> !name.startsWith("test-")).toList();
    assertThat(cells).as("the sweep must find the catalogue it is meant to police").isNotEmpty();

    Set<String> classified = new HashSet<>(NormalizerAllowlist.ADMITTED.keySet());
    classified.addAll(NormalizerAllowlist.EXCLUDED);

    assertThat(cells)
        .as("every CellHook in HookCatalog must be either admitted or explicitly excluded")
        .allMatch(classified::contains);
  }

  @Test
  void admitted_and_excluded_are_disjoint() {
    Set<String> both = new HashSet<>(NormalizerAllowlist.ADMITTED.keySet());
    both.retainAll(NormalizerAllowlist.EXCLUDED);
    assertThat(both).isEmpty();
  }

  @Test
  void the_allowlist_names_no_hook_that_does_not_exist() {
    List<String> cells = catalog.allCellHooks().stream().map(HookDescriptor::name).toList();
    Set<String> known = new HashSet<>(cells);

    assertThat(known)
        .as("a hook removed from HookCatalog must be removed from the allowlist too, "
            + "not linger as a stale entry")
        .containsAll(NormalizerAllowlist.ADMITTED.keySet())
        .containsAll(NormalizerAllowlist.EXCLUDED);
  }

  @Test
  void every_admitted_hook_carries_a_semantic_version() {
    NormalizerAllowlist.ADMITTED.forEach((id, version) ->
        assertThat(version).as("admitted hook '%s' must carry a version", id).isNotBlank());
  }

  @Test
  void the_intended_equivalence_criterion_excludes_the_named_hooks() {
    // mask/default/lookup/truncate/split collapse distinct values onto one that is NOT the
    // intended equivalence; kotlin is an arbitrary escape hatch with no fixed semantics to pin.
    assertThat(NormalizerAllowlist.EXCLUDED)
        .contains("mask", "default", "lookup", "truncate", "split", "kotlin");
  }
}
