package io.lumnik.extraction.spi.ask;

/** Outcome of a discovery run, surfaced to the CLI. */
public record DiscoveryResult(
    String scope, int tableCount, int columnCount, boolean enriched, String sourceType) {}
