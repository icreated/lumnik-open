package io.lumnik.hub.search.projection;

import io.lumnik.extraction.spi.search.ProjectionDefinition;
import io.lumnik.extraction.spi.search.ProjectionField;
import io.lumnik.extraction.spi.search.ProjectionFieldMode;
import io.lumnik.hub.search.SearchIndexManifest;
import io.lumnik.hub.search.SearchIndexManifest.Match;

/**
 * The one place SearchIndexManifest's shape is translated into the provider-neutral
 * ProjectionDefinition (design doc §1, §6) — so the SPI's shape and the manifest's own can never
 * independently drift.
 */
public final class ProjectionDefinitions {

    private ProjectionDefinitions() {}

    public static ProjectionDefinition from(SearchIndexManifest manifest) {
        return new ProjectionDefinition(manifest.entity(), manifest.language(),
            manifest.fields().stream()
                .map(f -> new ProjectionField(f.name(), modeOf(f.match()), f.facet()))
                .toList());
    }

    private static ProjectionFieldMode modeOf(Match match) {
        if (match == null) return null;
        return switch (match) {
            case EXACT -> ProjectionFieldMode.EXACT;
            case TEXT -> ProjectionFieldMode.TEXT;
            case FUZZY -> ProjectionFieldMode.FUZZY;
            case PREFIX -> ProjectionFieldMode.PREFIX;
        };
    }
}
