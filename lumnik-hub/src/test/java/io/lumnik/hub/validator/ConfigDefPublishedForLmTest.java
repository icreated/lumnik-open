package io.lumnik.hub.validator;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the hub publishes for a connector type is what {@code lm describe connector-type} decodes, and lm's golden tests
 * read the files this test keeps: real hub output, not a hand-written imitation of it. So the contract has one witness
 * on each side — a change to the serializer or to a declaration fails here until the file is regenerated, and the lm
 * suite then shows how the change reads.
 *
 * <p>Regenerate with {@code mvn test -pl lumnik-hub -Dtest=ConfigDefPublishedForLmTest -Dlumnik.describe.fixtures=update}.
 */
@QuarkusTest
class ConfigDefPublishedForLmTest {

    private static final Path DIR = Path.of("..", "lm", "internal", "output", "testdata", "describe");

    @Inject ConnectorValidationService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"csv-file", "jdbc-generic", "rest-generic"})
    void whatTheHubPublishesIsWhatLmIsTestedAgainst(String kind) throws Exception {
        String published = mapper.writerWithDefaultPrettyPrinter()
            .writeValueAsString(ConfigDefSerializer.toJson(service.configDefFor(kind).orElseThrow())) + "\n";
        Path file = DIR.resolve(kind + ".json");

        if ("update".equals(System.getProperty("lumnik.describe.fixtures"))) {
            Files.createDirectories(DIR);
            Files.writeString(file, published);
            return;
        }
        assertThat(file).as("lm's golden input for " + kind + " — regenerate it (see this class)").exists();
        assertThat(published).as("the hub now publishes something else for " + kind + " than lm is tested against "
            + "— regenerate " + file + " (see this class), then run lm's tests").isEqualTo(Files.readString(file));
    }
}
