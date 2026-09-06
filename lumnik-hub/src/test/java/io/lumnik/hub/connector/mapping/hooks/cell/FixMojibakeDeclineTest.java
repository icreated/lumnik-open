package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.HookCatalog;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When {@code fix-mojibake} CANNOT repair a value, it must leave the value alone and say so.
 *
 * <p>The repair reads the text's characters back as bytes and re-decodes them as UTF-8, which only
 * round-trips characters that fit in one byte. Anything above U+00FF has no byte to go back to and
 * was replaced with {@code '?'} on the way out — so a value that carried BOTH the mojibake pattern
 * and a character outside that range came back with its Japanese, Cyrillic or {@code €} gone.
 * Hexdump-verified before the fix: {@code B..n..dict } + {@code U+65E5 U+672C} came back as
 * {@code Bénédict} + {@code U+003F U+003F}. That is the scenario the hook is sold for — an ERP
 * export whose accented Latin text is double-encoded while a name or an amount is not Latin-1.
 *
 * <p>The detect/decline/count model this pins lives in {@link FixMojibakeHook}'s javadoc, once —
 * a hook author copying it should read it there, not here.
 */
@QuarkusTest
class FixMojibakeDeclineTest {

  @Inject HookCatalog catalog;
  private final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  /** "Bénédict" as it looks when UTF-8 bytes were read as Latin-1. */
  private static final String MOJIBAKE = "BÃ©nÃ©dict";
  private static final String REPAIRED = "Bénédict";
  private static final String CJK = "日本";
  /** A SURROGATE PAIR: two UTF-16 units, both above 0xFF. The shape a code-point-wise guard misses. */
  private static final String EMOJI = "😀";
  /** Matches the mojibake pattern, but the recovery ends on an incomplete UTF-8 sequence. */
  private static final String UNRECOVERABLE = "Ã©Ã";

  @ParameterizedTest(name = "{0}")
  @MethodSource("values")
  void a_repair_that_cannot_be_done_leaves_the_value_alone_and_counts(
      String label, String input, String expected, long expectedCellErrors) throws Exception {
    JsonNode params = YAML.readTree("columns: [name]\n");
    var row = new HashMap<String, Object>();
    row.put("name", input);
    var state = new MappingState();

    catalog.cell("fix-mojibake").apply(row, params, state);

    assertThat(row.get("name")).as("the stored value").isEqualTo(expected);
    assertThat(state.counters().cellErrors().get())
        .as("a declined repair must be COUNTED — an untouched value and a repaired one look the "
            + "same in the table, and only this number tells them apart")
        .isEqualTo(expectedCellErrors);
  }

  /**
   * A CSV cell can hold newlines — the reader supports quoted embedded ones, and a postal address
   * is the everyday case. The repair used to test the whole value with a {@code .*}-wrapped
   * pattern, and {@code .} does not cross a line terminator, so mojibake sitting after the first
   * newline was never even looked for. Found by review on #398 and left open then, because
   * changing it was a behaviour change rather than the fix that PR was making.
   */
  @Test
  void mojibake_after_a_newline_is_repaired_like_any_other() throws Exception {
    JsonNode params = YAML.readTree("columns: [address]\n");
    var row = new HashMap<String, Object>();
    row.put("address", "12 rue du Port\n" + MOJIBAKE);
    var state = new MappingState();

    catalog.cell("fix-mojibake").apply(row, params, state);

    assertThat(row.get("address"))
        .as("a multi-line cell is one value; where the mojibake sits inside it is not the "
            + "integrator's business")
        .isEqualTo("12 rue du Port\n" + REPAIRED);
    assertThat(state.counters().cellErrors().get()).isZero();
  }

  /** The decline must reach across a newline too, or a multi-line cell loses its CJK in silence. */
  @Test
  void a_multiline_cell_that_cannot_be_repaired_is_declined_and_counted() throws Exception {
    JsonNode params = YAML.readTree("columns: [address]\n");
    var row = new HashMap<String, Object>();
    String value = "12 rue du Port\n" + MOJIBAKE + " " + CJK;
    row.put("address", value);
    var state = new MappingState();

    catalog.cell("fix-mojibake").apply(row, params, state);

    assertThat(row.get("address")).isEqualTo(value);
    assertThat(state.counters().cellErrors().get()).isEqualTo(1L);
  }

  static Stream<Arguments> values() {
    return Stream.of(
        // --- the hook doing its job, and staying quiet about it ---
        Arguments.of("mojibake alone is repaired", MOJIBAKE, REPAIRED, 0L),
        Arguments.of("clean text is not touched", REPAIRED, REPAIRED, 0L),
        Arguments.of("clean text outside Latin-1 is not touched", "clean " + CJK,
            "clean " + CJK, 0L),
        // --- the hook declining, which it never used to do ---
        // Two shapes of "outside Latin-1", not one: a single BMP unit, and a surrogate PAIR.
        Arguments.of("mojibake mixed with CJK is declined, not destroyed",
            MOJIBAKE + " " + CJK, MOJIBAKE + " " + CJK, 1L),
        Arguments.of("mojibake mixed with an emoji is declined too",
            MOJIBAKE + " " + EMOJI, MOJIBAKE + " " + EMOJI, 1L),
        Arguments.of("a recovery that comes back garbled is declined AND counted",
            UNRECOVERABLE, UNRECOVERABLE, 1L));
  }
}
