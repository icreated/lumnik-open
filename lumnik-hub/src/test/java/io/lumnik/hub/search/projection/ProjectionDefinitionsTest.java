package io.lumnik.hub.search.projection;

import io.lumnik.extraction.spi.search.ProjectionDefinition;
import io.lumnik.extraction.spi.search.ProjectionField;
import io.lumnik.extraction.spi.search.ProjectionFieldMode;
import io.lumnik.hub.search.SearchIndexManifest;
import io.lumnik.hub.search.SearchIndexManifest.Field;
import io.lumnik.hub.search.SearchIndexManifest.Match;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

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

    // -- narrows (#1219): what a build serves that the declaration no longer declares -------------

    private static ProjectionDefinition def(String entity, ProjectionField... fields) {
        return new ProjectionDefinition(entity, "french", List.of(fields));
    }

    private static final ProjectionField CITY_TEXT_FACET = new ProjectionField("city", ProjectionFieldMode.TEXT, true);
    private static final ProjectionField NAME_TEXT = new ProjectionField("name", ProjectionFieldMode.TEXT, false);

    static Stream<Arguments> exposure() {
        return Stream.of(
            Arguments.of("the same declaration", def("widget", NAME_TEXT, CITY_TEXT_FACET), false),
            Arguments.of("a field added", def("widget", NAME_TEXT, CITY_TEXT_FACET,
                new ProjectionField("code", ProjectionFieldMode.EXACT, false)), false),
            Arguments.of("a returned-only field added", def("widget", NAME_TEXT, CITY_TEXT_FACET,
                new ProjectionField("code", null, false)), false),
            Arguments.of("another language", new ProjectionDefinition("widget", "english",
                List.of(NAME_TEXT, CITY_TEXT_FACET)), true),
            Arguments.of("the entity's name in another case", def("WIDGET", NAME_TEXT, CITY_TEXT_FACET), false),
            Arguments.of("a field removed", def("widget", NAME_TEXT), true),
            Arguments.of("a field no longer searched", def("widget", NAME_TEXT,
                new ProjectionField("city", null, true)), true),
            Arguments.of("a field no longer a facet", def("widget", NAME_TEXT,
                new ProjectionField("city", ProjectionFieldMode.TEXT, false)), true),
            Arguments.of("another entity", def("gadget", NAME_TEXT, CITY_TEXT_FACET), true));
    }

    @Test
    void aReturnedOnlyFieldMadeSearchableAndAFacetNarrowsNothing() {
        assertThat(ProjectionDefinitions.narrows(def("widget", new ProjectionField("city", null, false)),
            def("widget", CITY_TEXT_FACET))).isFalse();
    }

    /** The build matches with the mode it was built with: any other mode is a matching the declaration no
     *  longer holds — FUZZY → EXACT would keep serving approximate matches (Sergey's review). No partial order
     *  between the four modes is claimed, so every change withholds. */
    static Stream<Arguments> modePairs() {
        return Arrays.stream(ProjectionFieldMode.values()).flatMap(was ->
            Arrays.stream(ProjectionFieldMode.values()).map(is -> Arguments.of(was, is, was != is)));
    }

    @ParameterizedTest(name = "{0} -> {1} narrows: {2}")
    @MethodSource("modePairs")
    void anyChangeOfMatchModeNarrows(ProjectionFieldMode was, ProjectionFieldMode is, boolean narrows) {
        assertThat(ProjectionDefinitions.narrows(def("widget", new ProjectionField("city", was, false)),
            def("widget", new ProjectionField("city", is, false)))).isEqualTo(narrows);
    }

    @ParameterizedTest(name = "{0} narrows: {2}")
    @MethodSource("exposure")
    void aDeclarationNarrowsABuildWhenTheBuildServesWhatItNoLongerDeclares(
            String change, ProjectionDefinition declared, boolean narrows) {
        ProjectionDefinition built = def("widget", NAME_TEXT, CITY_TEXT_FACET);
        assertThat(ProjectionDefinitions.narrows(built, declared)).isEqualTo(narrows);
    }
}
