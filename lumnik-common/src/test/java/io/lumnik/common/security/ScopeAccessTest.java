package io.lumnik.common.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The métier-scope access boundary. Grant = Keycloak realm role {@code scope:<tag>}.
 * Opt-in semantics (ratified 2026-07-07): a user with zero {@code scope:*} roles is
 * unscoped — tenant-wide, behavior unchanged; a user with at least one is scope-bound —
 * only the granted scopes, {@code all}/blank rewritten to the granted set.
 */
class ScopeAccessTest {

    @AfterEach
    void clear() { CurrentRolesHolder.clear(); }

    // ── unscoped user (no scope:* roles) — today's behavior, untouched ──────

    @Test
    void unscopedUserHasNoBoundSet() {
        CurrentRolesHolder.set(Set.of("lm_user", "lm_admin"));
        assertThat(ScopeAccess.granted()).isEmpty();
    }

    @Test
    void unscopedUserQueryPassesThroughUnchanged() {
        CurrentRolesHolder.set(Set.of("lm_user"));

        var specific = ScopeAccess.forQuery("achat");
        assertThat(specific.allowed()).isTrue();
        assertThat(specific.tags()).containsExactly("achat");

        var all = ScopeAccess.forQuery("all");
        assertThat(all.allowed()).isTrue();
        assertThat(all.tags()).as("all stays unfiltered for an unscoped user").isEmpty();

        var blank = ScopeAccess.forQuery(null);
        assertThat(blank.allowed()).isTrue();
        assertThat(blank.tags()).isEmpty();
    }

    @Test
    void unscopedUserMayAskAnyScopeIncludingDefault() {
        CurrentRolesHolder.set(Set.of("lm_user"));
        assertThat(ScopeAccess.mayAsk("stock")).isTrue();
        assertThat(ScopeAccess.mayAsk(null)).as("unscoped keeps the 'system' default").isTrue();
    }

    // ── scope-bound user (≥1 scope:* role) ──────────────────────────────────

    @Test
    void boundUserSeesTheGrantedSetPrefixStripped() {
        CurrentRolesHolder.set(Set.of("lm_user", "scope:achat", "scope:compta"));
        assertThat(ScopeAccess.granted()).contains(Set.of("achat", "compta"));
    }

    @Test
    void boundUserAllowedScopePasses() {
        CurrentRolesHolder.set(Set.of("lm_user", "scope:achat", "scope:compta"));
        var d = ScopeAccess.forQuery("achat");
        assertThat(d.allowed()).isTrue();
        assertThat(d.tags()).containsExactly("achat");
    }

    @Test
    void boundUserForbiddenScopeIsDenied() {
        CurrentRolesHolder.set(Set.of("lm_user", "scope:achat", "scope:compta"));
        assertThat(ScopeAccess.forQuery("stock").allowed()).isFalse();
        assertThat(ScopeAccess.mayAsk("stock")).isFalse();
    }

    @Test
    void boundUserAllAndBlankRewriteToTheGrantedSet() {
        CurrentRolesHolder.set(Set.of("lm_user", "scope:achat", "scope:compta"));

        var all = ScopeAccess.forQuery("all");
        assertThat(all.allowed()).isTrue();
        assertThat(all.tags()).containsExactlyInAnyOrder("achat", "compta");

        var blank = ScopeAccess.forQuery("  ");
        assertThat(blank.allowed()).isTrue();
        assertThat(blank.tags()).containsExactlyInAnyOrder("achat", "compta");
    }

    @Test
    void boundUserMustNameAScopeForAnalyticAsk() {
        CurrentRolesHolder.set(Set.of("lm_user", "scope:achat"));
        assertThat(ScopeAccess.mayAsk(null)).as("no silent fall-through to 'system'").isFalse();
        assertThat(ScopeAccess.mayAsk("all")).isFalse();
        assertThat(ScopeAccess.mayAsk("achat")).isTrue();
    }

    @Test
    void whitespaceInTheRequestedScopeIsNormalized() {
        // Client-controlled input: "achat " must not 403 a user who holds scope:achat,
        // and " all" must be recognized as all — not treated as a scope named " all".
        CurrentRolesHolder.set(Set.of("lm_user", "scope:achat", "scope:compta"));

        var padded = ScopeAccess.forQuery("achat ");
        assertThat(padded.allowed()).isTrue();
        assertThat(padded.tags()).containsExactly("achat");

        var paddedAll = ScopeAccess.forQuery(" all");
        assertThat(paddedAll.allowed()).isTrue();
        assertThat(paddedAll.tags()).containsExactlyInAnyOrder("achat", "compta");

        assertThat(ScopeAccess.mayAsk(" achat")).isTrue();

        CurrentRolesHolder.set(Set.of("lm_user"));
        var unscoped = ScopeAccess.forQuery(" clients ");
        assertThat(unscoped.tags()).as("never filter on a tag that cannot match")
            .containsExactly("clients");
    }

}
