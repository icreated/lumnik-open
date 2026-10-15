package io.lumnik.extraction.spi.search;

/**
 * The caller's own request is unanswerable as given — a stale/tampered cursor, or a facet key no
 * eligible {@code SearchIndex} declares. Distinct from the base {@link IndexedSearchException}
 * (a technical provider failure): this one is never fixed by retrying, only by changing the
 * request, so the hub's gate maps it to a rejection rather than to "degraded".
 */
public class InvalidIndexedSearchQueryException extends IndexedSearchException {
    public InvalidIndexedSearchQueryException(String message) { super(message); }
    public InvalidIndexedSearchQueryException(String message, Throwable cause) { super(message, cause); }
}
