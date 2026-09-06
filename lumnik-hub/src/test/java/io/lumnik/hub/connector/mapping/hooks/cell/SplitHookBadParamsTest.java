package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.hooks.HookCatalog;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code into} that is not a LIST. Hook params reach the hook unvalidated — {@code MappingLoader}
 * passes them straight through — so the hook is the only place this can be caught, and a whole
 * chunk is what it costs when it is not.
 *
 * <p>Jackson answers the two mis-typings differently, which is why they are one table and not one
 * case: a scalar {@code into: x} is a {@code TextNode} whose {@code size()} is 0, so the loop never
 * ran and the hook did nothing at all — a manifest that declared columns and silently produced
 * none. An object {@code into: {a: b}} has {@code size() == 1} but {@code get(0)} is {@code null}
 * on an ObjectNode, so {@code names.get(i).asText()} threw a bare NPE out of the cell tier and
 * {@code ChunkExecutor} failed the entire chunk on it.
 *
 * <p>Both now refuse the same way the other param-checking hooks do
 * ({@code TruncateHook}, {@code RegexReplaceHook}, {@code KotlinCellHook}): a
 * {@link MappingValidationException} naming the parameter, thrown before any value is touched.
 */
@QuarkusTest
class SplitHookBadParamsTest {

  @Inject HookCatalog catalog;
  private final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  @ParameterizedTest(name = "{0}")
  @MethodSource("badIntoParams")
  void into_that_is_not_a_list_is_refused_by_name(String label, String paramsYaml) throws Exception {
    // Param-shape check moved to validateParams (the engine runs it once per spec, before any row).
    JsonNode params = YAML.readTree(paramsYaml);

    assertThatThrownBy(() -> catalog.cell("split").validateParams(params))
        .as("a mis-typed `into` must be refused by name, not answered with silence or an NPE")
        .isInstanceOf(MappingValidationException.class)
        .hasMessageContaining("into");
  }

  static Stream<Arguments> badIntoParams() {
    return Stream.of(
        // size() == 0 on a TextNode: the loop never ran, so the hook silently produced no columns.
        Arguments.of("a scalar string", "column: composite\nby: ','\ninto: x\n"),
        Arguments.of("a number", "column: composite\nby: ','\ninto: 5\n"),
        // size() == 1 on an ObjectNode but get(0) is null: a bare NPE aborted the whole chunk.
        Arguments.of("an object", "column: composite\nby: ','\ninto: {a: b}\n"));
  }
}
