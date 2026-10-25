package io.lumnik.common.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The scope grant primitive — opt-in semantics ratified 2026-07-07 (PR #131). */
class ScopeGrantsTest {

  @AfterEach
  void clear() { CurrentRolesHolder.clear(); }

  @Test
  void no_scope_roles_means_unscoped() {
    CurrentRolesHolder.set(Set.of("lm_user", "lm_integrator"));
    assertThat(ScopeGrants.granted()).isEmpty();
    assertThat(ScopeGrants.allows("clients")).isTrue();
    assertThat(ScopeGrants.allowsAny(List.of())).isTrue();       // even a scopeless resource
    assertThat(ScopeGrants.allowsAny(null)).isTrue();
  }

  @Test
  void scope_roles_bind_with_prefix_stripped() {
    CurrentRolesHolder.set(Set.of("lm_user", "scope:stock", "scope:achats"));
    assertThat(ScopeGrants.granted()).contains(Set.of("stock", "achats"));
    assertThat(ScopeGrants.allows("stock")).isTrue();
    assertThat(ScopeGrants.allows("clients")).isFalse();
  }

  /**
   * Reading asks for ANY, writing asks for ALL — the asymmetry that makes {@code allowsAny} the
   * wrong question at a write door. A bound user holding only {@code stock} must not publish a
   * resource into {@code clients} merely because {@code stock} is also on the list.
   */
  @Test
  void writing_needs_every_scope_not_merely_an_overlapping_one() {
    CurrentRolesHolder.set(Set.of("lm_user", "scope:stock"));

    assertThat(ScopeGrants.allowsAny(List.of("clients", "stock")))
        .as("reading a resource tagged with both is allowed — one grant is enough to see it")
        .isTrue();
    assertThat(ScopeGrants.allowsAll(List.of("clients", "stock")))
        .as("but publishing INTO both would widen 'clients' to a user who does not hold it")
        .isFalse();

    assertThat(ScopeGrants.allowsAll(List.of("stock"))).isTrue();
    assertThat(ScopeGrants.allowsAll(List.of())).as("a bound user cannot write a scopeless resource")
        .isFalse();
    assertThat(ScopeGrants.allowsAll(null)).isFalse();
  }

  @Test
  void an_unscoped_user_may_write_anywhere() {
    CurrentRolesHolder.set(Set.of("lm_user"));
    assertThat(ScopeGrants.allowsAll(List.of("clients", "stock"))).isTrue();
    assertThat(ScopeGrants.allowsAll(List.of())).isTrue();
    assertThat(ScopeGrants.allowsAll(null)).isTrue();
  }

  @Test
  void bound_user_needs_a_non_empty_intersection() {
    CurrentRolesHolder.set(Set.of("lm_user", "scope:stock"));
    assertThat(ScopeGrants.allowsAny(List.of("clients", "stock"))).isTrue();
    assertThat(ScopeGrants.allowsAny(List.of("clients"))).isFalse();
    assertThat(ScopeGrants.allowsAny(List.of())).isFalse();      // scopeless resource is invisible to a bound user
    assertThat(ScopeGrants.allowsAny(null)).isFalse();
  }

  @Test
  void null_scope_is_denied_for_a_bound_user_not_an_NPE() {
    // The primitive must be total: a future caller passing null must get a denial,
    // not a Set.contains(null) NPE from the immutable granted set.
    CurrentRolesHolder.set(Set.of("lm_user", "scope:stock"));
    assertThat(ScopeGrants.allows(null)).isFalse();
  }

  @Test
  void unset_context_reads_as_unscoped() {
    assertThat(ScopeGrants.granted()).isEmpty();
    assertThat(ScopeGrants.allows("anything")).isTrue();
  }
  /**
   * The refusal WARN renders the grants; a Set renders in hash order, so two identical refusals
   * could log two different lines and an exact-match SIEM rule would be brittle. One rendering,
   * sorted, for every door that logs it.
   */
  @Test
  void the_log_rendering_is_sorted_and_names_the_unscoped_case() {
    CurrentRolesHolder.set(Set.of("lm_user", "scope:stock", "scope:achats", "scope:magasin"));
    assertThat(ScopeGrants.grantedForLog()).isEqualTo("[achats, magasin, stock]");

    CurrentRolesHolder.set(Set.of("lm_user"));
    assertThat(ScopeGrants.grantedForLog()).isEqualTo("(unscoped)");
  }
}
