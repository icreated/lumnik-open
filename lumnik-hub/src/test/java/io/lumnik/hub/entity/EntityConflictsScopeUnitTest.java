package io.lumnik.hub.entity;

import io.lumnik.common.security.CurrentRolesHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;
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
    registry.put(new EntityManifest("Entity", "Stock", null, List.of("sku"), List.of(), List.of("magasin"), null, null, null));
    CurrentRolesHolder.set(Set.of("lm_user", "scope:ventes")); // bound, but NOT scope:magasin

    EntityConflictsResource res = new EntityConflictsResource();
    res.registry = registry;
    res.em = null; // any query attempt NPEs the test

    assertThat((List<?>) res.list(100, null).getEntity()).isEmpty();
  }

  @Test
  void bound_user_naming_a_non_granted_entity_gets_the_uniform_403() {
    EntityRegistry registry = new EntityRegistry();
    registry.put(new EntityManifest("Entity", "Stock", null, List.of("sku"), List.of(), List.of("magasin"), null, null, null));
    CurrentRolesHolder.set(Set.of("lm_user", "scope:ventes"));

    EntityConflictsResource res = new EntityConflictsResource();
    res.registry = registry;
    res.em = null;

    Response r = res.list(100, "Stock");
    assertThat(r.getStatus()).isEqualTo(403);
    assertThat(r.getEntity()).isEqualTo(Map.of("error", "scope not granted"));
  }

  @Test
  void naming_an_unknown_entity_is_a_404_without_touching_the_db() {
    EntityConflictsResource res = new EntityConflictsResource();
    res.registry = new EntityRegistry();
    res.em = null;

    assertThat(res.list(100, "Nope").getStatus()).isEqualTo(404);
  }

  @Test
  void an_empty_or_blank_entity_is_a_400_not_a_404_naming_nothing() {
    EntityConflictsResource res = new EntityConflictsResource();
    res.registry = new EntityRegistry();
    res.em = null;

    assertThat(res.list(100, "").getStatus()).isEqualTo(400);
    assertThat(res.list(100, "  ").getStatus()).isEqualTo(400);
  }
}
