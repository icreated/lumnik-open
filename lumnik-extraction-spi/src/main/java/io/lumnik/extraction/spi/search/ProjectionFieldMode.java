package io.lumnik.extraction.spi.search;

/** How one field is matched — {@code SearchIndexManifest.Match}'s own four values, provider-neutral. */
public enum ProjectionFieldMode { EXACT, TEXT, FUZZY, PREFIX }
