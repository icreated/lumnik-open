package io.lumnik.hub.connector.mapping.writer;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class ChunkTemplateRendererTest {

  @Inject ChunkTemplateRenderer renderer;

  private static Map<String, Object> row(Object... kv) {
    var m = new HashMap<String, Object>();
    for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
    return m;
  }

  /**
   * Every row of a RAG chunk template, as one table: what the template says, what the row and the
   * system context hold, and the text that reaches the corpus. What lands here is what the chat will
   * later be asked to answer from, so a value that silently renders as nothing is a fact deleted.
   */
  static Stream<Arguments> renderings() {
    return Stream.of(
        Arguments.of("a value from the row and one from the system context",
                     "Customer ${name} from ${country} (t=${_tenant})",
                     row("name", "Alice", "country", "FR"), row("_tenant", 1L),
                     "Customer Alice from FR (t=1)"),
        Arguments.of("a key present nowhere", "Hello ${missing}",
                     row("name", "X"), row(), "Hello "),
        Arguments.of("a key whose value is null", "Hello ${name}",
                     row("name", null), row(), "Hello "),
        Arguments.of("a system field", "hash=${_row_hash}",
                     row(), row("_row_hash", "abc123"), "hash=abc123"),
        Arguments.of("no placeholder at all", "static text", row(), row(), "static text"),

        // A source column may legitimately be named `_id` — ERP exports do it constantly. Routing
        // every `_` key to the system context alone meant the value silently rendered as nothing,
        // in every chunk, with no error and no warning.
        Arguments.of("a SOURCE column whose name starts with an underscore",
                     "item ${_id} / ${label}",
                     row("_id", "ERP-4711", "label", "Chair"), row(),
                     "item ERP-4711 / Chair"),
        Arguments.of("a system field the row also happens to carry — the system field wins",
                     "hash=${_row_hash}",
                     row("_row_hash", "from-the-erp"), row("_row_hash", "the-real-one"),
                     "hash=the-real-one"),

        // A malformed placeholder is not a placeholder. It stays as typed, where the integrator can
        // SEE it, rather than resolving to nothing — and above all it does not consume the text and
        // the well-formed placeholders that follow it.
        Arguments.of("an unclosed placeholder does not swallow what follows",
                     "a ${label b ${name} c", row("name", "Nina"), row(), "a ${label b Nina c"),
        Arguments.of("an empty placeholder", "x ${} y", row(), row(), "x ${} y"),
        Arguments.of("a placeholder padded with spaces", "x ${ name } y",
                     row("name", "Nina"), row(), "x ${ name } y"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("renderings")
  void rendersEveryPlaceholderShape(String axis, String template,
                                    Map<String, Object> row, Map<String, Object> sys,
                                    String expected) {
    assertThat(renderer.render(template, row, sys)).as("%s", axis).isEqualTo(expected);
  }
}
