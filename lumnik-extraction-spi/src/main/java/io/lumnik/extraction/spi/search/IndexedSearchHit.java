package io.lumnik.extraction.spi.search;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One ranked hit. Its identity is {@code (searchIndex, entity, key)}; the canonical record is read back
 * through the hub's own entity door, never from the index — the index is a proposal, not authority.
 *
 * @param searchIndex the {@code kind: SearchIndex} that produced the document.
 * @param entity      the {@code kind: Entity} the document projects.
 * @param key         canonical key column → value (several columns for a composite key); never empty.
 * @param score       provider relevance, comparable only within one result.
 * @param fields      returnable field → value, as declared by the SearchIndex.
 * @param highlights  returnable field → highlighted fragments; empty when none.
 */
public record IndexedSearchHit(String searchIndex, String entity, Map<String, String> key, double score,
                               Map<String, String> fields, Map<String, List<String>> highlights) {

    public IndexedSearchHit {
        requireName(searchIndex, "searchIndex");
        requireName(entity, "entity");
        Objects.requireNonNull(key, "key");
        if (key.isEmpty()) throw new IllegalArgumentException("key must name at least one column");
        key = Map.copyOf(key);
        fields = fields == null ? Map.of() : Map.copyOf(fields);
        highlights = highlights == null ? Map.of() : Map.copyOf(highlights);
    }

    private static void requireName(String value, String what) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(what + " must not be blank");
    }
}
