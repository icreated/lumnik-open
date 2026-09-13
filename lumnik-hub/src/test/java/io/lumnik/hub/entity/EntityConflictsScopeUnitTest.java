package io.lumnik.hub.entity;

import io.lumnik.common.security.CurrentRolesHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scope boundary on the aggregate: a bound user's aggregate silently SKIPS entities
 * outside their grants (never a 403 — the aggregate shows what you may see). The resource
 * has a null EntityManager, so reaching a query for a skipped entity fails loudly.
 */
class EntityConflictsScopeUnitTest {

  @AfterEach
  void clear() {
    CurrentRolesHolder.clear();
  }

  @Test
  void bound_user_aggregate_skips_non_granted_entities_without_touching_the_db() {
    EntityRegistry registry = new EntityRegistry();
    registry.put(new EntityManifest("Entity", "Stock", null, List.of("sku"), List.of(), List.of("magasin"), null, null));
    CurrentRolesHolder.set(Set.of("lm_user", "scope:ventes")); // bound, but NOT scope:magasin

    EntityConflictsResource res = new EntityConflictsResource();
    res.registry = registry;
    res.em = null; // any query attempt NPEs the test

    assertThat(res.list(100)).isEmpty();
  }
}
