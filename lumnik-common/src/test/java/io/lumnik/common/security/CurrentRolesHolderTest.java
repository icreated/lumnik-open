package io.lumnik.common.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Thread-local holder for the authenticated user's roles — the cross-module
 * mirror of {@code CurrentTenantHolder}: populated by the hub's auth filter,
 * readable from modules (lumnik-rag) that cannot see the hub at compile time.
 */
class CurrentRolesHolderTest {

    @AfterEach
    void clear() { CurrentRolesHolder.clear(); }

    @Test
    void emptyByDefaultNeverNull() {
        assertThat(CurrentRolesHolder.get()).isEmpty();
    }

    @Test
    void setThenGetRoundTrips() {
        CurrentRolesHolder.set(Set.of("lm_user", "scope:achat"));
        assertThat(CurrentRolesHolder.get()).containsExactlyInAnyOrder("lm_user", "scope:achat");
    }

    @Test
    void clearResetsToEmpty() {
        CurrentRolesHolder.set(Set.of("lm_user"));
        CurrentRolesHolder.clear();
        assertThat(CurrentRolesHolder.get()).isEmpty();
    }

    @Test
    void setNullClearsInsteadOfStoringNull() {
        CurrentRolesHolder.set(Set.of("lm_user"));
        CurrentRolesHolder.set(null);
        assertThat(CurrentRolesHolder.get()).isEmpty();
    }
}
