package io.lumnik.hub.search.projection;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The single place rebuild, catch-up, purge-drain and the reaper all derive their shared RunLock key
 * from (design doc §1's wrinkle, §2 D-lock) — never {@code core.search_index.id}, which purge-drain has
 * no row left to read by the time it runs. Deterministic: the same (tenant, name) pair always yields
 * the identical UUID.
 */
public final class SearchProjectionLockKey {

    private SearchProjectionLockKey() {}

    public static UUID of(long tenantId, String searchIndexName) {
        return UUID.nameUUIDFromBytes((tenantId + ":" + searchIndexName).getBytes(StandardCharsets.UTF_8));
    }
}
