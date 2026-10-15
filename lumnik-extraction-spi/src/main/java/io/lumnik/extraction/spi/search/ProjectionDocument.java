package io.lumnik.extraction.spi.search;

import java.util.Map;

/**
 * One document handed to {@link ProjectionSink#upsert}. {@code id} is the opaque canonical_key string
 * a mark row already carries (a {@code MatchKeyEncoding} output for match: mode, entity_id as text for
 * resolution: mode) — the identity a later {@code delete} call can still supply once the row backing
 * {@code key} no longer exists. {@code key} is the displayed, decomposed form: match column name(s) to
 * value(s), or the single synthetic pair {@code {"_entity_id": "<uuid>"}} for resolution: mode — mirrors
 * {@code IndexedSearchHit.key()} exactly, since the same shape crosses back out on the query side.
 */
public record ProjectionDocument(String id, Map<String, String> key, Map<String, String> fields) {

    public ProjectionDocument {
        if (id == null || id.isBlank())
            throw new IllegalArgumentException("id must not be blank");
        if (key == null || key.isEmpty())
            throw new IllegalArgumentException("key must name at least one column");
        key = Map.copyOf(key);
        fields = fields == null ? Map.of() : Map.copyOf(fields);
    }
}
