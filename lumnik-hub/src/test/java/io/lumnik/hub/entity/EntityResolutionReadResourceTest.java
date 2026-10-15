package io.lumnik.hub.entity;

import io.lumnik.common.security.CurrentRolesHolder;
import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.hub.testsupport.ResolvableManifests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static io.lumnik.hub.testsupport.ResolvableManifests.resolvable;
import static io.lumnik.hub.testsupport.ResolvableManifests.withoutResolution;
import static io.lumnik.hub.testsupport.WebApplicationExceptions.assertRefusal;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #613 lot #622's ambiguity API — the scope gate, same discipline as {@code
 * EntityScopeBindingUnitTest}: the resource under test has no {@code EntityIdentityRepository}
 * wired, so reaching the query would fail loudly (an NPE naming {@code this.repository}), which
 * is the positive proof a "passes the gate" test wants. Real-DB behaviour (the actual listing,
 * the ordering) is {@link io.lumnik.hub.entity.resolution.EntityIdentityRepositoryReadsTest}'s job.
 *
 * <p>The gate ({@code ResolutionGate.requireResolvable}) refuses by THROWING a {@code
 * WebApplicationException} — a real JAX-RS container unwraps it into the response, but calling
 * the method directly (as every test here does) sees the raised exception itself.
 */
class EntityResolutionReadResourceTest {

  private static EntityResolutionReadResource resourceServing(EntityManifest m) {
    EntityResolutionReadResource res = new EntityResolutionReadResource();
    res.registry = ResolvableManifests.registryServing(m);
    return res; // repository stays null: the gate must fire before it is ever touched
  }

  @BeforeEach
  void tenant() { CurrentTenantHolder.set(1L); }

  @AfterEach
  void clear() { CurrentTenantHolder.clear(); CurrentRolesHolder.clear(); }

  @Test
  void bound_user_foreign_entity_ambiguous_list_is_403() {
    CurrentRolesHolder.set(Set.of("lm_user", "scope:stock"));
    EntityResolutionReadResource res = resourceServing(resolvable("customer", List.of("clients")));
    assertRefusal(() -> res.ambiguous("customer", 100, 0), 403);
  }

  @Test
  void unknown_entity_ambiguous_list_is_404() {
    CurrentRolesHolder.set(Set.of("lm_user"));
    EntityResolutionReadResource res = resourceServing(null);
    assertRefusal(() -> res.ambiguous("nope", 100, 0), 404);
  }

  @Test
  void entity_without_resolution_policy_ambiguous_list_is_400() {
    CurrentRolesHolder.set(Set.of("lm_user"));
    EntityResolutionReadResource res = resourceServing(withoutResolution("customer", List.of()));
    assertRefusal(() -> res.ambiguous("customer", 100, 0), 400);
  }

  @Test
  void bound_user_granted_entity_ambiguous_list_passes_the_gate() {
    CurrentRolesHolder.set(Set.of("lm_user", "scope:stock"));
    EntityResolutionReadResource res = resourceServing(resolvable("articles", List.of("stock")));
    assertThatThrownBy(() -> res.ambiguous("articles", 100, 0))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("this.repository");
  }

  @Test
  void bound_user_foreign_entity_history_is_403() {
    CurrentRolesHolder.set(Set.of("lm_user", "scope:stock"));
    EntityResolutionReadResource res = resourceServing(resolvable("customer", List.of("clients")));
    assertRefusal(() -> res.history("customer", "erp", "cust-1"), 403);
  }

  @Test
  void unknown_entity_history_is_404() {
    CurrentRolesHolder.set(Set.of("lm_user"));
    EntityResolutionReadResource res = resourceServing(null);
    assertRefusal(() -> res.history("nope", "erp", "cust-1"), 404);
  }

  @Test
  void bound_user_granted_entity_history_passes_the_gate() {
    CurrentRolesHolder.set(Set.of("lm_user", "scope:stock"));
    EntityResolutionReadResource res = resourceServing(resolvable("articles", List.of("stock")));
    assertThatThrownBy(() -> res.history("articles", "erp", "cust-1"))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("this.repository");
  }
}
