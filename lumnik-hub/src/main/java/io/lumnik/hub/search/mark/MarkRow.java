package io.lumnik.hub.search.mark;

/**
 * One fetched {@code core.search_projection_mark} row (design doc §5c step 1): the row's own
 * {@code id} — carried through unchanged and used later for the exact-id delete discipline (never
 * a live-recomputed {@code id <= max} condition, so a concurrently reissued id is never silently
 * swept up alongside the batch that fetched it) — its {@link MarkKind}, and the canonical key it
 * names.
 */
public record MarkRow(long id, MarkKind kind, String canonicalKey) {}
