package io.lumnik.hub.export;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The manifests docs/apps/exports.md includes must pass the parser the apply door runs. */
class ExportDocExamplesTest {

    private static final Path EXAMPLES = Path.of("..", "docs", "apps", "exports");

    private final ExportManifestParser parser = new ExportManifestParser();

    @ParameterizedTest(name = "{0} parses")
    @CsvSource({
        "10-sftp-daily.yaml, AcmeCustomer, desk",
        "20-s3-hourly.yaml, AcmeCustomer, desk",
        "30-product-catalogue-s3.yaml, ProductWithCategory, catalogue"})
    void a_documented_example_passes_the_apply_door_parser(String file, String entity, String scope)
            throws IOException {
        JsonNode root = parser.readTree(Files.readString(EXAMPLES.resolve(file)));

        assertThat(parser.refuseInlineCredentials(root)).isEmpty();
        assertThat(parser.unknownKeys(root)).isEmpty();
        ExportManifest manifest = parser.parse(root);
        assertThat(manifest.entity()).isEqualTo(entity);
        assertThat(manifest.scope()).isEqualTo(scope);
    }
}
