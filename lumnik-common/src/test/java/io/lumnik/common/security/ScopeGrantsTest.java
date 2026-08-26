package io.lumnik.common.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The métier-scope grant primitive — opt-in semantics ratified 2026-07-07 (PR #131). */
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
}
