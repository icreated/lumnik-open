package io.lumnik.hub.connector.mapping;

import io.lumnik.hub.connector.mapping.spec.MappingLoader;
import io.lumnik.hub.connector.mapping.spec.MappingSpec;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A hook's param-shape rules are invariant across every row of a spec, yet the hooks re-checked
 * them inside {@code apply()} — once per row. The seam is {@code validateParams}, called ONCE per
 * transform ({@code MappingEngine.validateHookParams}) before any row is touched.
 *
 * <p>The probe that pins it: a spec with an invalid param and an EMPTY input list. With per-row
 * validation, {@code apply()} never runs on zero rows, so a bad manifest slipped through silently;
 * {@code validateParams} must refuse it regardless of the data. One case per converted hook, so a
 * hook that keeps its param check in {@code apply()} (where the empty chunk would skip it) fails
 * here — this is the guardian for the whole seam, not for a hand-picked two.
 */
@QuarkusTest
class HookValidateParamsTest {

  @Inject MappingEngine engine;

  private void transform(String yaml) {
    MappingSpec spec = MappingLoader.load(yaml);
    engine.transform(spec, List.<Map<String, Object>>of(), new MappingState(), false);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("badParamSpecs")
  void a_bad_param_is_refused_before_any_row(String hook, String tierBlock, String fragment) {
    String yaml = "id: t\nversion: 1\ntarget: x\n" + tierBlock;
    assertThatThrownBy(() -> transform(yaml))
        .as("%s must refuse its bad param in validateParams, before any row", hook)
        .isInstanceOf(MappingValidationException.class)
        .hasMessageContaining(fragment);
  }

  static Stream<Arguments> badParamSpecs() {
    return Stream.of(
        Arguments.of("map-columns", "row:\n  - map-columns: { rename: { CUST_NM: tenant_id } }\n", "tenant_id"),
        Arguments.of("truncate", "cell:\n  - truncate: { column: c, length: -1 }\n", "length"),
        Arguments.of("mask", "cell:\n  - mask: { column: c, mask-char: \"\" }\n", "mask-char"),
        Arguments.of("to-uuid", "cell:\n  - to-uuid: { column: c, on-invalid: bogus }\n", "on-invalid"),
        Arguments.of("split", "cell:\n  - split: { column: c, into: x }\n", "into"),
        Arguments.of("regex-replace", "cell:\n  - regex-replace: { column: c, pattern: \"[\" }\n", "regex"),
        Arguments.of("regex-extract",
            "cell:\n  - regex-extract: { column: c, pattern: \".*\", groups: { \"-1\": d } }\n", "groups"),
        // A container-node pattern must be a typed refusal, not a silent no-op (asText() flattens
        // it to "" otherwise) — the split-into defect, one hook over.
        Arguments.of("regex-replace container pattern",
            "cell:\n  - regex-replace: { column: c, pattern: [x] }\n", "pattern"),
        Arguments.of("regex-extract container pattern",
            "cell:\n  - regex-extract: { column: c, pattern: {a: b}, groups: { \"1\": d } }\n", "pattern"),
        Arguments.of("kotlin", "cell:\n  - kotlin: {}\n", "script"),
        // A scalar `table:` makes every lookup miss in silence (table.has(key) is false on a
        // non-object) — split's `into` shape, one hook over.
        Arguments.of("lookup scalar table", "cell:\n  - lookup: { column: c, table: nope }\n", "table"),
        // An UNKNOWN row/cell hook must also be refused before any row — the apply tiers name it, but
        // only inside the per-row loop an empty chunk skips.
        Arguments.of("unknown row hook", "row:\n  - no-such-row-hook: {}\n", "unknown row hook"),
        Arguments.of("unknown cell hook", "cell:\n  - no-such-cell-hook: {}\n", "unknown cell hook"));
  }

  /**
   * The house convention (pinned repo-wide in {@code MapColumnsRenameTest}): a knob written as
   * {@code key:} with nothing after the colon is null, which reads as ABSENT — not an error. Every
   * converted hook must honour it, so a bare optional knob is a no-op, never a spurious refusal or
   * a runtime crash on the literal string "null". The one exception is a REQUIRED knob (kotlin's
   * {@code script}), where absent — null included — is genuinely an error.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("nullOptionalKnobs")
  void a_null_optional_knob_reads_as_absent(String hook, String tierBlock) {
    String yaml = "id: t\nversion: 1\ntarget: x\n" + tierBlock;
    assertThatCode(() -> transform(yaml))
        .as("%s: a bare `key:` (null) is absent, not an error", hook)
        .doesNotThrowAnyException();
  }

  static Stream<Arguments> nullOptionalKnobs() {
    return Stream.of(
        Arguments.of("truncate length", "cell:\n  - truncate:\n      column: c\n      length:\n"),
        Arguments.of("mask mask-char", "cell:\n  - mask:\n      column: c\n      mask-char:\n"),
        Arguments.of("to-uuid on-invalid", "cell:\n  - to-uuid:\n      column: c\n      on-invalid:\n"),
        Arguments.of("split into", "cell:\n  - split:\n      column: c\n      into:\n"),
        Arguments.of("regex-replace pattern", "cell:\n  - regex-replace:\n      column: c\n      pattern:\n"),
        Arguments.of("regex-extract pattern",
            "cell:\n  - regex-extract:\n      column: c\n      pattern:\n      groups:\n        \"1\": d\n"));
  }

  @Test
  void filter_where_bad_regex_is_a_typed_refusal_naming_its_own_knob() {
    // filter-where is a DATASET hook (no validateParams — its apply runs once per chunk, empty
    // included), so the typed refusal lives in apply; it must be MappingValidationException, not a
    // raw PatternSyntaxException. And it must name filter-where's OWN knob, 'regex' — not 'pattern'
    // (the shared helper is parameterised on the knob; "regex" alone would also match "not a valid
    // regex", so assert the QUOTED knob).
    assertThatThrownBy(() -> transform("id: t\nversion: 1\ntarget: x\ndataset:\n  - filter-where: { column: c, regex: \"[\" }\n"))
        .isInstanceOf(MappingValidationException.class)
        .hasMessageContaining("'regex'");
  }

  @Test
  void filter_where_container_regex_is_refused_not_flattened() {
    assertThatThrownBy(() -> transform("id: t\nversion: 1\ntarget: x\ndataset:\n  - filter-where: { column: c, regex: {a: b} }\n"))
        .isInstanceOf(MappingValidationException.class)
        .hasMessageContaining("'regex'");
  }

  @Test
  void kotlin_null_script_is_still_required() {
    // A REQUIRED knob: null reads as absent, and absent script is genuinely an error.
    assertThatThrownBy(() -> transform("id: t\nversion: 1\ntarget: x\ncell:\n  - kotlin:\n      script:\n"))
        .isInstanceOf(MappingValidationException.class)
        .hasMessageContaining("script");
  }

  /**
   * The null-as-absent contract must hold in {@code apply()} too, not just {@code validateParams} —
   * a divergence (validateParams says absent, apply reads the literal "null" / present-null) is the
   * exact split this round closes. Driven with a real row so apply actually runs.
   */
  @Test
  void a_null_knob_is_absent_in_apply_too() {
    // split into: null → no into-columns declared, so no cellErrors (not "zero declared names") and
    // the source cell is untouched.
    MappingState split = new MappingState();
    var out = transformRow("cell:\n  - split:\n      column: c\n      into:\n", split, "A,B");
    assertThat(split.counters().cellErrors().get()).as("null `into` is absent, not 0 names").isZero();
    assertThat(out.get(0)).containsEntry("c", "A,B");

    // regex-replace pattern: null → no-op, the value is not matched against the literal "null".
    var repl = transformRow("cell:\n  - regex-replace:\n      column: c\n      pattern:\n      replacement: X\n",
        new MappingState(), "hello");
    assertThat(repl.get(0)).containsEntry("c", "hello");

    // regex-extract pattern: null → no extraction, no crash, no columns invented.
    var extract = transformRow(
        "cell:\n  - regex-extract:\n      column: c\n      pattern:\n      groups:\n        \"1\": part\n",
        new MappingState(), "hello");
    assertThat(extract.get(0)).containsEntry("c", "hello").doesNotContainKey("part");
  }

  private List<Map<String, Object>> transformRow(String tierBlock, MappingState state, String cellValue) {
    MappingSpec spec = MappingLoader.load("id: t\nversion: 1\ntarget: x\n" + tierBlock);
    Map<String, Object> row = new HashMap<>();
    row.put("c", cellValue);
    return engine.transform(spec, List.of(row), state, false);
  }

  @Test
  void valid_params_do_not_throw_on_empty_rows() {
    assertThatCode(() -> transform("""
        id: t
        version: 1
        target: x
        row:
          - map-columns: { rename: { CUST_NM: name } }
        cell:
          - truncate: { column: name, length: 5 }
        """))
      .doesNotThrowAnyException();
  }
}
