package io.lumnik.hub.search.projection;

import io.lumnik.extraction.spi.search.ProjectionDefinition;
import io.lumnik.extraction.spi.search.ProjectionField;
import io.lumnik.extraction.spi.search.ProjectionFieldMode;
import io.lumnik.hub.search.SearchIndexManifest;
import io.lumnik.hub.search.SearchIndexManifest.Match;
import io.lumnik.hub.search.SearchIndexManifestParser;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

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

    /**
     * Whether a generation built from {@code built} serves something {@code declared} no longer declares
     * (#1219): another entity or language, a field it no longer returns or facets on, or a field it no longer
     * searches the same way. The build matches with its own modes and language, and no order between them is
     * claimed (FUZZY → EXACT would keep matching approximately), so any change there narrows. Such a build
     * must not answer until it is replaced. A new field, or a field newly searched or faceted, narrows
     * nothing: the build is older, never wider.
     */
    public static boolean narrows(ProjectionDefinition built, ProjectionDefinition declared) {
        if (!built.entity().equalsIgnoreCase(declared.entity()) || !built.language().equals(declared.language()))
            return true;
        Map<String, ProjectionField> now = new HashMap<>();
        for (ProjectionField f : declared.fields()) now.put(f.name(), f);
        for (ProjectionField was : built.fields()) {
            ProjectionField is = now.get(was.name());
            if (is == null || (was.mode() != null && was.mode() != is.mode()) || (was.facet() && !is.facet())) return true;
        }
        return false;
    }

    /** A stored declaration's definition, or empty when its YAML no longer parses (parser-version skew): what
     *  was built from it cannot be told, so every caller then errs towards withholding. */
    public static Optional<ProjectionDefinition> fromStored(String manifestYaml) {
        try {
            return Optional.of(from(new SearchIndexManifestParser().parse(manifestYaml)));
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
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
