package io.lumnik.extraction.spi.search;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One direct-search request as a provider receives it. The authorization restriction is part of the
 * type, not an option: there is no constructor without a tenant and an allowed-entity set, and no
 * value meaning "every entity".
 *
 * @param tenantId        the caller's tenant; non-positive is refused (the request path's fail-closed rule).
 * @param allowedEntities the {@code kind: Entity} names the caller may read, resolved by the hub from
 *                        PostgreSQL at query time. Empty means nothing is visible — never everything.
 * @param text            the user's plain text.
 * @param filters         declared facet filters, field → value; empty when none.
 * @param limit           maximum hits to return, at least 1.
 * @param cursor          opaque continuation from a previous {@link IndexedSearchResult#nextCursor()}, or null.
 */
public record IndexedSearchQuery(long tenantId, Set<String> allowedEntities, String text,
                                 Map<String, String> filters, int limit, String cursor) {

    public IndexedSearchQuery {
        if (tenantId <= 0) {
            throw new IllegalArgumentException("tenantId must be positive, was " + tenantId);
        }
        Objects.requireNonNull(allowedEntities, "allowedEntities");
        allowedEntities = Set.copyOf(allowedEntities);
        for (String e : allowedEntities) {
            if (e.isBlank()) throw new IllegalArgumentException("allowedEntities holds a blank name");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        filters = filters == null ? Map.of() : Map.copyOf(filters);
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1, was " + limit);
        }
    }
}
