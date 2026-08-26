package io.lumnik.hub.connector.mapping;

import java.util.Map;

/**
 * What one {@code MappingEngine.prepareEmbeddings} rehearsal pass produced: the row_hash → vector
 * map to thread into {@code transformAndWrite}'s {@code preparedVectors} parameter, and how many
 * CELLS this pass's own cell hooks nulled.
 *
 * <p>{@code prepareEmbeddings} mutates the caller's row maps IN PLACE — the same maps
 * {@code transformAndWrite} transforms again right afterwards ({@code MappingSpecItemWriter}) —
 * on purpose: two hooks are content-nondeterministic across separate transform passes
 * ({@code to-uuid}'s {@code generate: true}, {@code enrich-context}'s {@code ${now}}), and
 * {@code RagWriter.useCachedVectors} looks its prepared vector up by the TRANSFORMED row's hash,
 * so the two passes must land on the exact same value or the prepared vector silently misses and
 * the chunk is dropped from RAG. Because the rows are shared, this pass's cell-error count is the
 * REAL one: every cell hook starts {@code if (v == null) return;}, so the write pass that follows
 * sees the already-nulled cell and contributes 0 for it. The caller sums this count with the
 * write pass's own — correct whether or not prepare ran, since the write-only count is exactly
 * this pass's count when it did, and this field is 0 (no prepare) when it didn't.
 */
public record PrepareOutcome(Map<String, float[]> preparedVectors, long cellErrors) {}
