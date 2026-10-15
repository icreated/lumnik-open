package io.lumnik.extraction.spi.search;

/** The three states a client must be able to tell apart — none of them looks like "zero hits". */
public enum SearchAvailability {
    /** A provider is installed and answering. */
    AVAILABLE,
    /** No provider is installed (Open edition, Pro without Search). */
    UNAVAILABLE,
    /** A provider is installed but cannot answer: backend down, misconfigured, failing. */
    DEGRADED
}
