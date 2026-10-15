package io.lumnik.hub.entity;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code Resolution.physicalIdentityColumn} was extracted from {@code EntityViewCompiler}'s own
 * inline lookup, which built a canonical-&gt;physical map via repeated {@code put(...)} — for a
 * source that (invalidly, but {@code EntityManifestParser} does not forbid it) maps the same
 * canonical column from two physical columns, that let the LAST declared mapping win. The
 * extracted method instead returned on the FIRST match, silently changing which physical column a
 * duplicate-mapped manifest resolves identity from. Pinned so this shared method stays byte-for-
 * byte behavior-preserving for every existing caller, not just a refactor in appearance.
 */
class EntityManifestResolutionTest {

  @Test
  void physical_identity_column_resolves_the_last_duplicate_mapping_like_the_view_compiler_did() {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("OLD_ID", "customer_id");
    map.put("NEW_ID", "customer_id");
    EntityManifest.Source source = new EntityManifest.Source("t_dup", map);
    EntityManifest.Resolution resolution =
        new EntityManifest.Resolution(Map.of("t_dup", "customer_id"), List.of());

    assertThat(resolution.physicalIdentityColumn(source))
        .as("EntityViewCompiler's pre-existing canonToSrc lookup let the LAST duplicate mapping "
            + "win (a Map built via repeated put()); this shared method must match that exactly, "
            + "not silently pick the first one")
        .isEqualTo("NEW_ID");
  }
}
