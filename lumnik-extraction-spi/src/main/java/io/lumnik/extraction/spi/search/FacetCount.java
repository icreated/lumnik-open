package io.lumnik.extraction.spi.search;

/** One value of a declared facet field and how many hits carry it. */
public record FacetCount(String value, long count) {}
