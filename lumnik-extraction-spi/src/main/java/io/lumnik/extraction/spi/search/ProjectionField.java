package io.lumnik.extraction.spi.search;

/**
 * One declared field, mirroring {@code SearchIndexManifest.Field} exactly (name, match mode, facet) but
 * owned by the SPI, never importing the hub's own manifest type across the module boundary. Every field
 * here is returnable — {@code SearchIndexManifest}'s own rule, "every declared field is returned" — so
 * there is no separate returnable flag; {@code mode == null} means returned but not searchable.
 */
public record ProjectionField(String name, ProjectionFieldMode mode, boolean facet) {

    public ProjectionField {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("name must not be blank");
    }
}
