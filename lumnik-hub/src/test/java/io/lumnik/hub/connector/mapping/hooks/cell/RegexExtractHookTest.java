package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.hooks.HookCatalog;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code regex-extract} against the two things that go wrong, which fail in opposite directions.
 *
 * <p><b>A manifest error was swallowed</b> — three of them. A {@code groups} key that is not a
 * number, or names a group the pattern does not have, was skipped by
 * {@code catch (NumberFormatException | IllegalStateException) { // ignore bad group index }} and
 * by an {@code idx <= groupCount()} test. The declared column was never created and the run said
 * nothing, so a typo in a manifest looked exactly like a pattern that never matched.
 *
 * <p><b>And one manifest error took the whole chunk down.</b> A NEGATIVE key reaches
 * {@code m.group(-1)}, which throws {@code IndexOutOfBoundsException} — not one of the two the
 * catch names — so it escaped the lambda, escaped the hook, and {@code ChunkExecutor} failed the
 * chunk on a stack trace naming neither the hook nor the knob. Every row in that chunk, lost to a
 * minus sign.
 *
 * <p>All four are knowable from the manifest alone: the pattern is in it, so its group count is
 * too. They are refused by name, before the cell is read, like {@code split}'s {@code into} and
 * {@code to-uuid}'s {@code on-invalid}.
 *
 * <p>Why a miss is not counted, and why the columns appear anyway, lives in
 * {@link RegexExtractHook}'s javadoc — once.
 */
@QuarkusTest
class RegexExtractHookTest {

  @Inject HookCatalog catalog;
  private final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  /** Two capture groups, so key "3" is out of range and key "2" is the last valid one. */
  private static final String TWO_GROUPS =
      "column: ref\npattern: '(\\d{4})-(\\d{2})'\ngroups: {\"1\": year, \"2\": month}\n";

  private Map<String, Object> apply(String paramsYaml, Object cell, MappingState s)
      throws Exception {
    JsonNode params = YAML.readTree(paramsYaml);
    var row = new HashMap<String, Object>();
    row.put("ref", cell);
    catalog.cell("regex-extract").apply(row, params, s);
    return row;
  }

  /** Param-shape validation moved to validateParams (the engine runs it once per spec, no row). */
  private void validateParams(String paramsYaml) throws Exception {
    catalog.cell("regex-extract").validateParams(YAML.readTree(paramsYaml));
  }

  // ---- manifest errors: refused by name, never swallowed and never fatal ----

  @ParameterizedTest(name = "{0}")
  @MethodSource("badGroupKeys")
  void a_groups_key_the_pattern_cannot_honour_is_refused(String label, String key)
      throws Exception {
    String yaml = "column: ref\npattern: '(\\d{4})-(\\d{2})'\ngroups: {\"" + key + "\": target}\n";

    assertThatThrownBy(() -> validateParams(yaml))
        .as("%s", label)
        .isInstanceOf(MappingValidationException.class)
        .hasMessageContaining("regex-extract")
        .hasMessageContaining("groups");
  }

  static Stream<Arguments> badGroupKeys() {
    return Stream.of(
        Arguments.of("a key that is not a number", "first"),
        // This one used to abort the whole chunk: m.group(-1) throws IndexOutOfBoundsException,
        // which the hook's catch did not name.
        Arguments.of("a negative key", "-1"),
        Arguments.of("a key past the pattern's group count", "3"));
  }

  // ---- data: a pattern that does not match ----

  /**
   * The promise is structural, not numeric: a declared column exists on every row. Both
   * non-outcomes are covered — a value that did not match, and a cell that was empty so the
   * pattern never ran. The second is the one an earlier draft of this hook got wrong: its
   * {@code v == null} guard returned above the fill, so a chunk of empty cells created no columns
   * at all and the javadoc's "always exists" was false.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("nonOutcomes")
  void a_declared_column_exists_even_when_nothing_was_extracted(String label, Object cell)
      throws Exception {
    var state = new MappingState();
    var row = apply(TWO_GROUPS, cell, state);

    assertThat(row).as("%s", label).containsKey("year").containsKey("month");
    assertThat(row.get("year")).isNull();
    assertThat(row.get("month")).isNull();
    assertThat(state.counters().cellErrors().get())
        .as("a miss is a property of a heterogeneous column, not of a broken value — counting it "
            + "would fire on every row of an optional field and bury the parse-hook failures that "
            + "share this one scalar")
        .isZero();
  }

  /**
   * The key errors — including the one that used to kill the chunk — are refused by validateParams,
   * once per spec, so no row need arrive at all: an all-null column can no longer let a bad manifest
   * through for a whole run. (The range check, which needs the pattern's group count, now compiles
   * once there too rather than waiting for the first non-null row.)
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("patternFreeKeyErrors")
  void a_key_error_that_needs_no_pattern_is_refused_even_on_an_empty_cell(String label, String key)
      throws Exception {
    String yaml = "column: ref\npattern: '(\\d{4})-(\\d{2})'\ngroups: {\"" + key + "\": target}\n";

    assertThatThrownBy(() -> validateParams(yaml))
        .as("%s", label)
        .isInstanceOf(MappingValidationException.class)
        .hasMessageContaining("groups");
  }

  static Stream<Arguments> patternFreeKeyErrors() {
    return Stream.of(
        Arguments.of("a key that is not a number", "first"),
        Arguments.of("a negative key — the one that used to abort the chunk", "-1"));
  }

  static Stream<Arguments> nonOutcomes() {
    return Stream.of(
        Arguments.of("the value did not match", "unknown"),
        Arguments.of("the cell was empty, so the pattern never ran", null));
  }

  // ---- the floor: what must keep working ----

  /**
   * The single-slot Pattern cache must not return a stale Pattern when a different spec's pattern
   * arrives through the shared app-scoped singleton. A → B → A, each extracted correctly, proves the
   * slot recompiles on a pattern change rather than reusing the previous compile.
   */
  @Test
  void the_pattern_cache_recompiles_when_the_pattern_changes() throws Exception {
    var a = apply("column: ref\npattern: '(\\d{4})-(\\d{2})'\ngroups: {\"1\": year}\n", "2024-01",
        new MappingState());
    assertThat(a).containsEntry("year", "2024");

    var b = apply("column: ref\npattern: '([A-Z]+)'\ngroups: {\"1\": code}\n", "ABC", new MappingState());
    assertThat(b).containsEntry("code", "ABC");

    var a2 = apply("column: ref\npattern: '(\\d{4})-(\\d{2})'\ngroups: {\"1\": year}\n", "2030-12",
        new MappingState());
    assertThat(a2).containsEntry("year", "2030");
  }

  @Test
  void a_match_writes_the_groups_and_counts_nothing() throws Exception {
    var state = new MappingState();
    var row = apply(TWO_GROUPS, "2024-01", state);

    assertThat(row).containsEntry("year", "2024").containsEntry("month", "01");
    assertThat(state.counters().cellErrors().get()).isZero();
  }

  @Test
  void group_zero_is_the_whole_match_and_is_a_valid_key() throws Exception {
    var state = new MappingState();
    var row = apply("column: ref\npattern: '(\\d{4})-(\\d{2})'\ngroups: {\"0\": whole}\n",
        "2024-01", state);

    assertThat(row).containsEntry("whole", "2024-01");
    assertThat(state.counters().cellErrors().get()).isZero();
  }

  /**
   * An alternation leaves the branch that did not run with a null group. The pattern MATCHED, so
   * the hook did what it declared — that null is the answer, not a failure.
   */
  @Test
  void a_group_that_did_not_participate_writes_null_without_counting() throws Exception {
    var state = new MappingState();
    var row = apply("column: ref\npattern: '(a)|(b)'\ngroups: {\"1\": first, \"2\": second}\n",
        "a", state);

    assertThat(row).containsEntry("first", "a");
    assertThat(row.get("second")).isNull();
    assertThat(state.counters().cellErrors().get()).isZero();
  }
}
