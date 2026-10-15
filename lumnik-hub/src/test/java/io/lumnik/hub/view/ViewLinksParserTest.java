package io.lumnik.hub.view;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** #1050: spec.links — a cell that leads to another View (filtered) or to an external https page. */
class ViewLinksParserTest {

    private final ViewManifestParser parser = new ViewManifestParser();

    private static String withLinks(String links) {
        return """
            apiVersion: apps.lumnik.io/v1
            kind: View
            metadata:
              name: categories
            spec:
              scope: catalogue
              columns: [category_id, name, active]
              links:
            """ + links.indent(4);
    }

    private static final String TO_VIEW = """
        - column: name
          targetView: Products
          filters:
            category_id: "{category_id}"
        """;

    private static final String TO_URL = """
        - column: category_id
          url: "https://erp.example.com/categories/{category_id}?tab=1"
        """;

    @Test void a_view_without_links_has_none() {
        String yaml = """
            apiVersion: apps.lumnik.io/v1
            kind: View
            metadata:
              name: categories
            spec:
              scope: catalogue
              columns: [name]
            """;
        assertThat(parser.parse(yaml).links()).isEmpty();
    }

    @Test void a_link_to_another_view_keeps_its_filters_and_a_normalized_target() {
        ViewLink l = parser.parse(withLinks(TO_VIEW)).links().get(0);
        assertThat(l.column()).isEqualTo("name");
        assertThat(l.targetView()).isEqualTo("products");
        assertThat(l.filters()).isEqualTo(Map.of("category_id", "{category_id}"));
        assertThat(l.url()).isNull();
    }

    @Test void an_external_link_keeps_its_template() {
        ViewLink l = parser.parse(withLinks(TO_URL)).links().get(0);
        assertThat(l.url()).isEqualTo("https://erp.example.com/categories/{category_id}?tab=1");
        assertThat(l.targetView()).isNull();
        assertThat(l.filters()).isEmpty();
    }

    @Test void a_filter_may_mix_literal_text_and_placeholders() {
        String links = TO_VIEW.replace("\"{category_id}\"", "\"CAT-{category_id}\"");
        assertThat(parser.parse(withLinks(links)).links().get(0).filters())
            .isEqualTo(Map.of("category_id", "CAT-{category_id}"));
    }

    private void refused(String links, String... messageParts) {
        assertThatThrownBy(() -> parser.parse(withLinks(links)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContainingAll(messageParts);
    }

    @Test void links_must_be_a_list() {
        refused("column: name\n", "spec.links");
    }

    @Test void the_source_column_must_be_declared_by_the_view() {
        refused(TO_VIEW.replace("column: name", "column: nope"), "spec.links[0].column", "nope", "spec.columns");
    }

    @Test void a_placeholder_must_be_a_declared_column() {
        refused(TO_VIEW.replace("{category_id}", "{ghost}"), "spec.links[0]", "{ghost}", "spec.columns");
        refused(TO_URL.replace("{category_id}", "{ghost}"), "spec.links[0].url", "{ghost}", "spec.columns");
    }

    @Test void a_link_has_exactly_one_destination() {
        refused("- column: name\n", "spec.links[0]", "targetView", "url");
        refused(TO_VIEW + "  url: \"https://x.example.com/\"\n", "spec.links[0]", "targetView", "url");
    }

    @Test void an_internal_link_needs_filters_and_an_external_one_takes_none() {
        refused("- column: name\n  targetView: products\n", "spec.links[0].filters");
        refused("- column: name\n  targetView: products\n  filters: {}\n", "spec.links[0].filters");
        refused(TO_URL + "  filters:\n    a: b\n", "spec.links[0].filters", "url");
    }

    @Test void a_filter_value_must_be_text_and_its_column_a_plain_identifier() {
        refused(TO_VIEW.replace("\"{category_id}\"", "5"), "spec.links[0].filters", "string");
        refused(TO_VIEW.replace("category_id: ", "\"a b\": "), "spec.links[0].filters", "a b");
    }

    @Test void the_target_must_be_a_view_name() {
        refused(TO_VIEW.replace("Products", "../apply"), "spec.links[0].targetView");
        refused(TO_VIEW.replace("Products", "apply"), "spec.links[0].targetView");
    }

    @Test void filters_keep_the_order_they_were_written_in() {
        String links = """
            - column: name
              targetView: products
              filters:
                zeta: "{category_id}"
                alpha: "{category_id}"
                mid: "{category_id}"
            """;
        assertThat(parser.parse(withLinks(links)).links().get(0).filters().keySet())
            .containsExactly("zeta", "alpha", "mid");
    }

    @Test void two_links_on_one_column_are_refused() {
        refused(TO_VIEW + TO_VIEW, "spec.links[1]", "name");
    }

    @Test void at_most_sixteen_links() {
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 17; i++) many.append("- column: name\n  url: \"https://x.example.com/").append(i).append("\"\n");
        // seventeen entries on one column would also be duplicates: spread the refusal on the count
        assertThatThrownBy(() -> parser.parse(withLinks(many.toString())))
            .hasMessageContaining("at most 16");
    }

    // The full catalogue of refused templates lives in LinkTemplateTest, the one description shared by every
    // kind that links out; these three only prove this parser really goes through it.
    @ParameterizedTest(name = "{0} is refused as an external link")
    @ValueSource(strings = {
        "http://erp.example.com/c/{category_id}",
        "https://user:pw@erp.example.com/c/{category_id}",
        "https://{category_id}.example.com/",
    })
    void a_malformed_or_unsafe_external_url_is_refused(String url) {
        refused("- column: category_id\n  url: '" + url + "'\n", "spec.links[0].url");
    }

    @Test void a_placeholder_may_sit_in_path_query_or_fragment() {
        for (String url : List.of("https://erp.example.com/c/{category_id}",
                                  "https://erp.example.com:8443/c?id={category_id}",
                                  "https://erp.example.com/c#{category_id}")) {
            assertThat(parser.parse(withLinks("- column: category_id\n  url: \"" + url + "\"\n")).links()).hasSize(1);
        }
    }

    @Test void unknown_keys_inside_a_link_are_reported_with_their_path() {
        String yaml = withLinks(TO_VIEW + "  label: oops\n");
        assertThat(parser.unknownKeys(yaml)).containsExactly("spec.links[0].label");
    }

    @Test void links_are_part_of_the_honored_surface() {
        assertThat(ViewManifestParser.honoredSurface()).contains("links").contains("targetView");
    }

    @Test void the_documented_example_passes_the_apply_door_parser() throws Exception {
        String yaml = java.nio.file.Files.readString(
            java.nio.file.Path.of("..", "docs", "apps", "demo", "views", "30-categories-links.yaml"));
        assertThat(parser.unknownKeys(yaml)).isEmpty();
        assertThat(parser.parse(yaml).links()).extracting(ViewLink::column).containsExactly("name", "category_id");
    }
}
