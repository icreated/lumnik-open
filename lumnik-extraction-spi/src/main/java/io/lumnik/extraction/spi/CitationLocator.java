package io.lumnik.extraction.spi;

import java.util.Optional;

/**
 * Resolves a citation row-hash to the mapping that produced the chunk — the first hop of the
 * citation drill-down (hash → mapping → dynamic table → source row). Implemented by the chunk
 * store on the closed side; the open hub injects it as {@code Instance<>}. When no
 * implementation is on the classpath there is no citation store, so no hash can resolve —
 * the caller answers 404, never a schema error. (Found by the Act 2 dry-run: the drill-down
 * used to query {@code rag.chunk} in a native-SQL string — a runtime schema coupling no
 * compile-level guard can see.)
 */
public interface CitationLocator {

    /** The mapping_id whose chunk carries this row_hash, if the store knows it. */
    Optional<String> mappingIdForRowHash(String rowHash);
}
