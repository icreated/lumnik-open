package io.lumnik.hub.search.projection;

import io.lumnik.extraction.spi.search.ProjectionDefinition;
import io.lumnik.extraction.spi.search.ProjectionField;
import io.lumnik.extraction.spi.search.ProjectionFieldMode;
import io.lumnik.hub.search.SearchIndexManifest;
import io.lumnik.hub.search.SearchIndexManifest.Field;
import io.lumnik.hub.search.SearchIndexManifest.Match;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design doc §1/§6/test 25: every SearchIndexManifest.Match value maps to the identically-named
 * ProjectionFieldMode; match == null (returned, not searchable) becomes mode == null; facet carries
 * straight across; entity/language copied verbatim. The one translation point — proves the SPI's
 * shape and the manifest's own stay in lockstep, not just that the types compile.
 */
class ProjectionDefinitionsTest {

    @Test
    void translatesEveryMatchModeOneToOne() {
        SearchIndexManifest manifest = new SearchIndexManifest("idx", "widget", "french", List.of(
            new Field("a", Match.EXACT, false),
            new Field("b", Match.TEXT, true),
            new Field("c", Match.FUZZY, false),
            new Field("d", Match.PREFIX, false),
            new Field("e", null, true)));

        ProjectionDefinition def = ProjectionDefinitions.from(manifest);

        assertThat(def.entity()).isEqualTo("widget");
        assertThat(def.language()).isEqualTo("french");
        assertThat(def.fields()).containsExactly(
            new ProjectionField("a", ProjectionFieldMode.EXACT, false),
            new ProjectionField("b", ProjectionFieldMode.TEXT, true),
            new ProjectionField("c", ProjectionFieldMode.FUZZY, false),
            new ProjectionField("d", ProjectionFieldMode.PREFIX, false),
            new ProjectionField("e", null, true));
    }
}
