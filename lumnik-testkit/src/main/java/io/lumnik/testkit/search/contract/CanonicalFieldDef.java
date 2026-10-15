package io.lumnik.testkit.search.contract;

import io.lumnik.extraction.spi.search.ProjectionFieldMode;

/** {@code mode == null} means the field is returnable but not searchable — mirrors
 *  {@link io.lumnik.extraction.spi.search.ProjectionField}'s own contract exactly. */
public record CanonicalFieldDef(String name, ProjectionFieldMode mode, boolean facet) {}
