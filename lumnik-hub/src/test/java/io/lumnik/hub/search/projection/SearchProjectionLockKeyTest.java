package io.lumnik.hub.search.projection;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design doc §1's wrinkle / D-lock: rebuild, catch-up and purge-drain must derive the SAME key for the
 * same (tenant, index) pair — purge-drain has no core.search_index.id left to read by the time it
 * runs, so every caller derives from (tenant_id, search_index name) alone, deterministically.
 */
class SearchProjectionLockKeyTest {

    @Test
    void deterministicForTheSamePair() {
        UUID first = SearchProjectionLockKey.of(1L, "idx");
        UUID second = SearchProjectionLockKey.of(1L, "idx");
        assertThat(first).isEqualTo(second);
    }

    @Test
    void collisionFreeOverDistinctPairs() {
        Set<UUID> keys = new HashSet<>();
        for (long tenant = 1; tenant <= 20; tenant++) {
            for (String name : new String[] {"a", "b", "c", "d", "e"}) {
                assertThat(keys.add(SearchProjectionLockKey.of(tenant, name))).isTrue();
            }
        }
        assertThat(keys).hasSize(100);
    }
}
