package io.lumnik.extraction.spi.search;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A successful answer — possibly with zero hits, which then means exactly "nothing matched".
 *
 * @param hits       ranked, best first.
 * @param facets     declared facet field → value counts; empty when none were asked for.
 * @param nextCursor opaque continuation, or null on the last page.
 * @param asOf       instant the answering index reflects hub truth up to, or null when unknown. Stated in
 *                   committed terms: every change committed to the hub at or before {@code asOf}, and
 *                   covered by the mark producers, is reflected. Out-of-band hard {@code DELETE}s on
 *                   ingested tables leave no mark and are NOT covered (#732) — they need a rebuild.
 *                   A {@code resolution:} entity's value follows catch-up once the resolution pass has
 *                   covered every ingesting run (#748); until then it holds behind the oldest uncovered one.
 *                   An older value is always honest; a newer one never is.
 */
public record IndexedSearchResult(List<IndexedSearchHit> hits, Map<String, List<FacetCount>> facets,
                                  String nextCursor, Instant asOf) {

    public IndexedSearchResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
        facets = facets == null ? Map.of() : Map.copyOf(facets);
    }

    /** No hits, no facets, no continuation. */
    public static IndexedSearchResult empty() {
        return new IndexedSearchResult(List.of(), Map.of(), null, null);
    }
}
