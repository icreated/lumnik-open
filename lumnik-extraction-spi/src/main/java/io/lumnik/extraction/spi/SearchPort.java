package io.lumnik.extraction.spi;

import java.util.List;

public interface SearchPort {

    /** Unique port identifier (typically matches an ExtractionSink.name()). */
    String name();

    /**
     * Search ranked chunks within tenant scope, optionally filtered by tag intersection.
     * Implementations must be safe for concurrent calls.
     */
    List<SearchResult> search(String query, int topK, long tenantId, List<String> scope);
}
