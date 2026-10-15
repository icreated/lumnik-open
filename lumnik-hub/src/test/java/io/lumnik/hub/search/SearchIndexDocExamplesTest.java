package io.lumnik.hub.search;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The manifests docs/search/search-indexes.md includes must pass the parser the apply door runs. */
class SearchIndexDocExamplesTest {

    private static final Path EXAMPLES = Path.of("..", "docs", "search", "search-indexes");

    private final SearchIndexManifestParser parser = new SearchIndexManifestParser();

    @ParameterizedTest(name = "{0} parses")
    @ValueSource(strings = {"10-customers.yaml", "20-orders-return-only.yaml", "30-products-title.yaml",
                            "40-products-erp-link.yaml"})
    void a_documented_example_passes_the_apply_door_parser(String file) throws IOException {
        JsonNode root = parser.readTree(Files.readString(EXAMPLES.resolve(file)));

        assertThat(parser.unknownKeys(root)).isEmpty();
        SearchIndexManifest manifest = parser.parse(root);
        assertThat(manifest.searchableFields()).isNotEmpty();
        assertThat(manifest.returnableFields()).containsAll(manifest.searchableFields());
    }

    @Test
    void the_documented_erp_link_is_a_link_on_a_declared_field() throws IOException {
        SearchIndexManifest manifest = parser.parse(Files.readString(EXAMPLES.resolve("40-products-erp-link.yaml")));

        assertThat(manifest.links()).containsExactly(
            new SearchIndexManifest.Link("reference", "https://erp.example.com/articles/{reference}"));
    }
}
