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
        // #1441: a constant a hook writes into a column is text. A list or a map used to be flattened to ""
        // by asText(), and a null to the word "null" — in silence. Refused by name, before any row.
        Arguments.of("enrich-context list value", "dataset:\n  - enrich-context: { tags: [a, b], batch: 3 }\n",
            "enrich-context hook: 'tags' must be text, a number or a boolean (got array)"),
        Arguments.of("defaults list value", "row:\n  - defaults: { qty: 0, tags: [] }\n",
            "defaults hook: 'tags' must be text, a number or a boolean (got array)"),
        Arguments.of("defaults null value", "row:\n  - defaults:\n      qty:\n",
            "defaults hook: 'qty' must be text, a number or a boolean (got null)"),
        Arguments.of("regex-extract list column name",
            "cell:\n  - regex-extract: { column: c, pattern: \"(.*)\", groups: { \"1\": [a] } }\n",
            "regex-extract hook: 'groups.1' must be text, a number or a boolean (got array)"),
        Arguments.of("regex-extract null column name",
            "cell:\n  - regex-extract:\n      column: c\n      pattern: \"(.*)\"\n      groups:\n        \"1\":\n",
            "regex-extract hook: 'groups.1' must be text, a number or a boolean (got null)"),
        Arguments.of("default list value", "cell:\n  - default: { column: c, value: [a] }\n",
            "default hook: 'value' must be text, a number or a boolean (got array)"),
        Arguments.of("lookup map replacement", "cell:\n  - lookup: { column: c, table: { Y: { a: 1 } } }\n",
            "lookup hook: 'table.Y' must be text, a number or a boolean (got object)"),
        // #1463: a list or a map on-missing used to be read as the literal "" in silence.
        Arguments.of("lookup list on-missing", "cell:\n  - lookup: { column: c, table: { Y: A }, on-missing: [a] }\n",
            "lookup hook: 'on-missing' must be text, a number or a boolean (got array)"),
        Arguments.of("lookup map on-missing", "cell:\n  - lookup: { column: c, table: { Y: A }, on-missing: { a: 1 } }\n",
            "lookup hook: 'on-missing' must be text, a number or a boolean (got object)"),
        Arguments.of("aggregator list collect",
            "row:\n  - aggregator: { emit-on-change: k, merge: { tags: { collect: [a] } } }\n",
            "aggregator hook: 'merge.tags.collect' must be text, a number or a boolean (got array)"),
        Arguments.of("aggregator merge entry not a map",
            "row:\n  - aggregator: { emit-on-change: k, merge: { tags: a } }\n",
            "aggregator hook: 'merge.tags' must be a map {collect, into, dedup, sep} (got string)"),
        Arguments.of("aggregator merge not a map",
            "row:\n  - aggregator: { emit-on-change: k, merge: [a] }\n",
            "aggregator hook: 'merge' must be a map of column to {collect, into, dedup, sep} (got array)"),
        // #1465: a value filter-where compares with is text, `in` a list of them. asText() read a list or a map as ""
        // (keeping only blank cells), a null as the word "null", and iterating a scalar `in` found nothing — no filter.
        Arguments.of("filter-where list equals", "dataset:\n  - filter-where: { column: c, equals: [a] }\n",
            "filter-where hook: 'equals' must be text, a number or a boolean (got array)"),
        Arguments.of("filter-where map equals", "dataset:\n  - filter-where: { column: c, equals: { a: b } }\n",
            "filter-where hook: 'equals' must be text, a number or a boolean (got object)"),
        Arguments.of("filter-where null equals", "dataset:\n  - filter-where:\n      column: c\n      equals:\n",
            "filter-where hook: 'equals' must be text, a number or a boolean (got null)"),
        Arguments.of("filter-where scalar in", "dataset:\n  - filter-where: { column: c, in: ACTIVE }\n",
            "filter-where hook: 'in' must be a list of text, numbers or booleans (got string)"),
        Arguments.of("filter-where map in", "dataset:\n  - filter-where: { column: c, in: { a: b } }\n",
            "filter-where hook: 'in' must be a list of text, numbers or booleans (got object)"),
        Arguments.of("filter-where list inside in", "dataset:\n  - filter-where: { column: c, in: [a, [b]] }\n",
            "filter-where hook: 'in[1]' must be text, a number or a boolean (got array)"),
        // A bare `in:` read as absent would let every row through: refused, like a bare `equals:`.
        Arguments.of("filter-where null in", "dataset:\n  - filter-where:\n      column: c\n      in:\n",
            "filter-where hook: 'in' must be a list of text, numbers or booleans (got null)"),
        // #1465: `column:` names a column, in every tier — one rule, in the catalog every hook is validated through.
        Arguments.of("dataset list column", "dataset:\n  - filter-where: { column: [c], equals: a }\n",
            "filter-where hook: 'column' must be text, a number or a boolean (got array)"),
        Arguments.of("row map column", "row:\n  - coalesce: { column: { a: b }, from: [x] }\n",
            "coalesce hook: 'column' must be text, a number or a boolean (got object)"),
        Arguments.of("cell list column", "cell:\n  - lookup: { column: [c], table: { Y: true } }\n",
            "lookup hook: 'column' must be text, a number or a boolean (got array)"),
        Arguments.of("cell null column", "cell:\n  - truncate:\n      column:\n",
            "truncate hook: 'column' must be text, a number or a boolean (got null)"),
        // #1472: the column names the other hooks read. asText() read a list or a map as "" and a null as the word
        // "null", and iterating a scalar where a list belongs found nothing — no sort, no check, in silence.
        Arguments.of("sort-by scalar columns", "dataset:\n  - sort-by: { columns: c }\n",
            "sort-by hook: 'columns' must be a list of text, numbers or booleans (got string)"),
        Arguments.of("sort-by list inside columns", "dataset:\n  - sort-by: { columns: [a, [b]] }\n",
            "sort-by hook: 'columns[1]' must be text, a number or a boolean (got array)"),
        Arguments.of("require-headers map columns", "dataset:\n  - require-headers: { columns: { a: b } }\n",
            "require-headers hook: 'columns' must be a list of text, numbers or booleans (got object)"),
        Arguments.of("require-headers null any-of", "dataset:\n  - require-headers:\n      any-of:\n",
            "require-headers hook: 'any-of' must be a list of text, numbers or booleans (got null)"),
        Arguments.of("coalesce scalar from", "row:\n  - coalesce: { column: c, from: x }\n",
            "coalesce hook: 'from' must be a list of text, numbers or booleans (got string)"),
        Arguments.of("coalesce map inside from", "row:\n  - coalesce: { column: c, from: [x, { a: b }] }\n",
            "coalesce hook: 'from[1]' must be text, a number or a boolean (got object)"),
        Arguments.of("validate-row scalar required", "row:\n  - validate-row: { required: code }\n",
            "validate-row hook: 'required' must be a list of text, numbers or booleans (got string)"),
        Arguments.of("validate-row null required", "row:\n  - validate-row:\n      required:\n",
            "validate-row hook: 'required' must be a list of text, numbers or booleans (got null)"),
        Arguments.of("aggregator list emit-on-change", "row:\n  - aggregator: { emit-on-change: [k] }\n",
            "aggregator hook: 'emit-on-change' must be text, a number or a boolean (got array)"),
        Arguments.of("aggregator null emit-on-change", "row:\n  - aggregator:\n      emit-on-change:\n",
            "aggregator hook: 'emit-on-change' must be text, a number or a boolean (got null)"),
        Arguments.of("aggregator scalar keep-first", "row:\n  - aggregator: { emit-on-change: k, keep-first: k }\n",
            "aggregator hook: 'keep-first' must be a list of text, numbers or booleans (got string)"),
        Arguments.of("aggregator list into",
            "row:\n  - aggregator: { emit-on-change: k, merge: { tags: { collect: t, into: [csv] } } }\n",
            "aggregator hook: 'merge.tags.into' must be text, a number or a boolean (got array)"),
        Arguments.of("aggregator map sep",
            "row:\n  - aggregator: { emit-on-change: k, merge: { tags: { collect: t, sep: { a: b } } } }\n",
            "aggregator hook: 'merge.tags.sep' must be text, a number or a boolean (got object)"),
        Arguments.of("split-row list explode", "row:\n  - split-row: { explode: [items] }\n",
            "split-row hook: 'explode' must be text, a number or a boolean (got array)"),
        Arguments.of("split-row null by", "row:\n  - split-row:\n      by:\n",
            "split-row hook: 'by' must be text, a number or a boolean (got null)"),
        Arguments.of("split-row list sep", "row:\n  - split-row: { by: tags, sep: [\";\"] }\n",
            "split-row hook: 'sep' must be text, a number or a boolean (got array)"),
        Arguments.of("clean scalar columns", "cell:\n  - clean: { columns: name }\n",
            "clean hook: 'columns' must be a list of text, numbers or booleans (got string)"),
        Arguments.of("parse-date map inside formats", "cell:\n  - parse-date: { column: d, formats: [{ a: b }] }\n",
            "parse-date hook: 'formats[0]' must be text, a number or a boolean (got object)"),
        Arguments.of("split list inside into", "cell:\n  - split: { column: c, into: [a, [b]] }\n",
            "split hook: 'into[1]' must be text, a number or a boolean (got array)"),
        // A list written but empty configures a hook that does nothing — or, for clean, the opposite of an absent
        // `columns` (every column): refused, as a bare key is. clean's every-column form is `columns: ["*"]`.
        Arguments.of("clean empty columns", "cell:\n  - clean: { columns: [] }\n",
            "clean hook: 'columns' must list at least one value (got an empty list)"),
        Arguments.of("clean null columns", "cell:\n  - clean:\n      columns:\n",
            "clean hook: 'columns' must be a list of text, numbers or booleans (got null)"),
        Arguments.of("sort-by empty columns", "dataset:\n  - sort-by: { columns: [] }\n",
            "sort-by hook: 'columns' must list at least one value (got an empty list)"),
        Arguments.of("require-headers empty any-of", "dataset:\n  - require-headers: { any-of: [] }\n",
            "require-headers hook: 'any-of' must list at least one value (got an empty list)"),
        Arguments.of("coalesce empty from", "row:\n  - coalesce: { column: c, from: [] }\n",
            "coalesce hook: 'from' must list at least one value (got an empty list)"),
        Arguments.of("validate-row empty required", "row:\n  - validate-row: { required: [] }\n",
            "validate-row hook: 'required' must list at least one value (got an empty list)"),
        Arguments.of("aggregator empty keep-first", "row:\n  - aggregator: { emit-on-change: k, keep-first: [] }\n",
            "aggregator hook: 'keep-first' must list at least one value (got an empty list)"),
        Arguments.of("parse-date empty formats", "cell:\n  - parse-date: { column: d, formats: [] }\n",
            "parse-date hook: 'formats' must list at least one value (got an empty list)"),
        Arguments.of("filter-where empty in", "dataset:\n  - filter-where: { column: c, in: [] }\n",
            "filter-where hook: 'in' must list at least one value (got an empty list)"),
        // The column a row hook groups or splits by: empty or blank text names no column.
        Arguments.of("aggregator empty emit-on-change", "row:\n  - aggregator: { emit-on-change: \"\" }\n",
            "aggregator hook: 'emit-on-change' must name a column (got blank text)"),
        Arguments.of("aggregator blank emit-on-change", "row:\n  - aggregator: { emit-on-change: \"   \" }\n",
            "aggregator hook: 'emit-on-change' must name a column (got blank text)"),
        Arguments.of("split-row empty by", "row:\n  - split-row: { by: \"\" }\n",
            "split-row hook: 'by' must name a column (got blank text)"),
        Arguments.of("split-row blank by", "row:\n  - split-row: { by: \"   \" }\n",
            "split-row hook: 'by' must name a column (got blank text)"),
        Arguments.of("split-row empty explode", "row:\n  - split-row: { explode: \"\" }\n",
            "split-row hook: 'explode' must name a column (got blank text)"),
        Arguments.of("split-row blank explode", "row:\n  - split-row: { explode: \"   \" }\n",
            "split-row hook: 'explode' must name a column (got blank text)"),
        // #1482: a blank element of a list of column names names no column — refused like an empty list. Value
        // lists (filter-where's `in`) are not column names and keep their blanks.
        Arguments.of("sort-by empty element", "dataset:\n  - sort-by: { columns: [\"\"] }\n",
            "sort-by hook: 'columns[0]' must name a column (got blank text)"),
        Arguments.of("clean blank element", "cell:\n  - clean: { columns: [\"   \"] }\n",
            "clean hook: 'columns[0]' must name a column (got blank text)"),
        Arguments.of("require-headers blank columns element",
            "dataset:\n  - require-headers: { columns: [a, \"  \"] }\n",
            "require-headers hook: 'columns[1]' must name a column (got blank text)"),
        Arguments.of("require-headers empty any-of element", "dataset:\n  - require-headers: { any-of: [\"\"] }\n",
            "require-headers hook: 'any-of[0]' must name a column (got blank text)"),
        Arguments.of("coalesce empty from element", "row:\n  - coalesce: { column: c, from: [x, \"\"] }\n",
            "coalesce hook: 'from[1]' must name a column (got blank text)"),
        Arguments.of("validate-row blank required element", "row:\n  - validate-row: { required: [\"   \"] }\n",
            "validate-row hook: 'required[0]' must name a column (got blank text)"),
        Arguments.of("aggregator empty keep-first element",
            "row:\n  - aggregator: { emit-on-change: k, keep-first: [\"\"] }\n",
            "aggregator hook: 'keep-first[0]' must name a column (got blank text)"),
        Arguments.of("split blank into element", "cell:\n  - split: { column: c, into: [a, \"   \"] }\n",
            "split hook: 'into[1]' must name a column (got blank text)"),
        // #1482: the knobs still read with asText() at run time — each read typed, and a closed set checked, once.
        Arguments.of("parse-number list decimal-separator",
            "cell:\n  - parse-number: { column: c, decimal-separator: [\",\"] }\n",
            "parse-number hook: 'decimal-separator' must be text, a number or a boolean (got array)"),
        Arguments.of("parse-number unknown decimal-separator",
            "cell:\n  - parse-number: { column: c, decimal-separator: comma }\n",
            "parse-number hook: 'decimal-separator' must be \"auto\", \".\" or \",\""),
        Arguments.of("clean map normalize-unicode", "cell:\n  - clean: { normalize-unicode: { a: b } }\n",
            "clean hook: 'normalize-unicode' must be text, a number or a boolean (got object)"),
        Arguments.of("clean unknown normalize-unicode", "cell:\n  - clean: { normalize-unicode: nfc }\n",
            "clean hook: 'normalize-unicode' must be \"NFC\", \"NFD\", \"NFKC\" or \"NFKD\""),
        Arguments.of("aggregator unknown into",
            "row:\n  - aggregator: { emit-on-change: k, merge: { tags: { collect: t, into: list } } }\n",
            "aggregator hook: 'merge.tags.into' must be \"array\", \"set\" or \"csv\""),
        // An aggregator without its key put every row in one group, without a word: a hook that groups nothing.
        Arguments.of("aggregator without emit-on-change", "row:\n  - aggregator: { keep-first: [k] }\n",
            "aggregator hook: 'emit-on-change' is required"),
        Arguments.of("split list by", "cell:\n  - split: { column: c, by: [\";\"], into: [a, b] }\n",
            "split hook: 'by' must be text, a number or a boolean (got array)"),
        Arguments.of("parse-date list zone", "cell:\n  - parse-date: { column: d, formats: [yyyy], zone: [UTC] }\n",
            "parse-date hook: 'zone' must be text, a number or a boolean (got array)"),
        Arguments.of("parse-date map zone", "cell:\n  - parse-date: { column: d, formats: [yyyy], zone: { a: b } }\n",
            "parse-date hook: 'zone' must be text, a number or a boolean (got object)"),
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
            "cell:\n  - regex-extract:\n      column: c\n      pattern:\n      groups:\n        \"1\": d\n"),
        Arguments.of("parse-number decimal-separator", "cell:\n  - parse-number:\n      column: c\n      decimal-separator:\n"),
        Arguments.of("clean normalize-unicode", "cell:\n  - clean:\n      normalize-unicode:\n"),
        Arguments.of("aggregator into",
            "row:\n  - aggregator:\n      emit-on-change: k\n      merge:\n        tags:\n          collect: t\n          into:\n"),
        Arguments.of("split by", "cell:\n  - split:\n      column: c\n      by:\n"),
        Arguments.of("parse-date zone", "cell:\n  - parse-date:\n      column: d\n      zone:\n"));
  }

  @Test
  void filter_where_bad_regex_is_a_typed_refusal_naming_its_own_knob() {
    // filter-where is a DATASET hook whose regex check stays in apply (it runs once per chunk, empty
    // included), so the typed refusal lives there; it must be MappingValidationException, not a
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

    // clean normalize-unicode: null → no normalization, not Normalizer.Form.valueOf("null") thrown on every row.
    var clean = transformRow("cell:\n  - clean:\n      normalize-unicode:\n", new MappingState(), "hello");
    assertThat(clean.get(0)).containsEntry("c", "hello");
  }

  private List<Map<String, Object>> transformRow(String tierBlock, MappingState state, String cellValue) {
    MappingSpec spec = MappingLoader.load("id: t\nversion: 1\ntarget: x\n" + tierBlock);
    Map<String, Object> row = new HashMap<>();
    row.put("c", cellValue);
    return engine.transform(spec, List.of(row), state, false);
  }

  /**
   * #1486: a boolean knob is a YAML boolean, or absent. asBoolean(false) read "yes", a list or a map as false — the
   * default, in silence. Refused by its shape, before any row; the value is never quoted. (#1489: the manifest's YAML
   * reader resolves only true/false as booleans, so an unquoted {@code yes} is the text too.)
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("badBooleanKnobs")
  void a_boolean_knob_that_is_not_a_boolean_is_refused(String knob, String tierBlock, String message) {
    String yaml = "id: t\nversion: 1\ntarget: x\n" + tierBlock;
    assertThatThrownBy(() -> transform(yaml))
        .as("%s must be a boolean", knob)
        .isInstanceOf(MappingValidationException.class)
        .hasMessage(message);
  }

  static Stream<Arguments> badBooleanKnobs() {
    return Stream.of(
        Arguments.of("parse-number negatives-in-parens \"yes\"",
            "cell:\n  - parse-number: { column: c, negatives-in-parens: \"yes\" }\n",
            "parse-number hook: 'negatives-in-parens' must be true or false (got string)"),
        Arguments.of("parse-number negatives-in-parens list",
            "cell:\n  - parse-number: { column: c, negatives-in-parens: [true] }\n",
            "parse-number hook: 'negatives-in-parens' must be true or false (got array)"),
        Arguments.of("parse-number integer \"yes\"", "cell:\n  - parse-number: { column: c, integer: \"yes\" }\n",
            "parse-number hook: 'integer' must be true or false (got string)"),
        Arguments.of("clean strip \"yes\"", "cell:\n  - clean: { strip: \"yes\" }\n",
            "clean hook: 'strip' must be true or false (got string)"),
        Arguments.of("clean strip unquoted yes", "cell:\n  - clean: { strip: yes }\n",
            "clean hook: 'strip' must be true or false (got string)"),
        Arguments.of("split into-array unquoted On", "cell:\n  - split: { column: c, into-array: On }\n",
            "split hook: 'into-array' must be true or false (got string)"),
        Arguments.of("clean strip list", "cell:\n  - clean: { strip: [true] }\n",
            "clean hook: 'strip' must be true or false (got array)"),
        Arguments.of("clean remove-control-chars \"yes\"", "cell:\n  - clean: { remove-control-chars: \"yes\" }\n",
            "clean hook: 'remove-control-chars' must be true or false (got string)"),
        Arguments.of("clean remove-control-chars map", "cell:\n  - clean: { remove-control-chars: { a: b } }\n",
            "clean hook: 'remove-control-chars' must be true or false (got object)"),
        Arguments.of("split into-array \"yes\"", "cell:\n  - split: { column: c, into-array: \"yes\" }\n",
            "split hook: 'into-array' must be true or false (got string)"),
        Arguments.of("split into-array list", "cell:\n  - split: { column: c, into-array: [true] }\n",
            "split hook: 'into-array' must be true or false (got array)"),
        Arguments.of("aggregator dedup \"yes\"",
            "row:\n  - aggregator: { emit-on-change: k, merge: { tags: { collect: t, dedup: \"yes\" } } }\n",
            "aggregator hook: 'merge.tags.dedup' must be true or false (got string)"),
        Arguments.of("aggregator dedup list",
            "row:\n  - aggregator: { emit-on-change: k, merge: { tags: { collect: t, dedup: [true] } } }\n",
            "aggregator hook: 'merge.tags.dedup' must be true or false (got array)"),
        Arguments.of("to-uuid generate \"yes\"", "cell:\n  - to-uuid: { column: c, generate: \"yes\" }\n",
            "to-uuid hook: 'generate' must be true or false (got string)"),
        Arguments.of("to-uuid generate list", "cell:\n  - to-uuid: { column: c, generate: [true] }\n",
            "to-uuid hook: 'generate' must be true or false (got array)"),
        Arguments.of("parse-email lower \"yes\"", "cell:\n  - parse-email: { column: c, lower: \"yes\" }\n",
            "parse-email hook: 'lower' must be true or false (got string)"),
        Arguments.of("parse-email lower list", "cell:\n  - parse-email: { column: c, lower: [true] }\n",
            "parse-email hook: 'lower' must be true or false (got array)"),
        Arguments.of("regex-extract required \"yes\"",
            "cell:\n  - regex-extract: { column: c, pattern: \"(.*)\", groups: { \"1\": d }, required: \"yes\" }\n",
            "regex-extract hook: 'required' must be true or false (got string)"),
        // The quoted words are text, as in every other boolean a manifest declares (ConfigDef's BOOLEAN).
        Arguments.of("clean strip quoted true", "cell:\n  - clean: { strip: \"true\" }\n",
            "clean hook: 'strip' must be true or false (got string)"),
        Arguments.of("split into-array number", "cell:\n  - split: { column: c, into-array: 1 }\n",
            "split hook: 'into-array' must be true or false (got number)"));
  }

  /** #1486: true, false and a bare key (absent: the default) stay valid on every boolean knob. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("booleanKnobs")
  void a_boolean_knob_keeps_true_false_and_bare(String knob, String tierBlock) {
    String yaml = "id: t\nversion: 1\ntarget: x\n" + tierBlock;
    assertThatCode(() -> transform(yaml)).as(knob).doesNotThrowAnyException();
  }

  static Stream<Arguments> booleanKnobs() {
    return Stream.of(
        Arguments.of("parse-number", "cell:\n  - parse-number: { column: c, negatives-in-parens: true, integer: false }\n"),
        Arguments.of("clean", "cell:\n  - clean: { strip: false, remove-control-chars: true }\n"),
        Arguments.of("clean bare", "cell:\n  - clean:\n      strip:\n      remove-control-chars:\n"),
        Arguments.of("split", "cell:\n  - split: { column: c, into-array: true }\n"),
        Arguments.of("split bare", "cell:\n  - split:\n      column: c\n      into-array:\n"),
        Arguments.of("aggregator dedup bare",
            "row:\n  - aggregator:\n      emit-on-change: k\n      merge:\n        tags:\n          collect: t\n          dedup:\n"),
        Arguments.of("to-uuid", "cell:\n  - to-uuid: { column: c, generate: true }\n"),
        Arguments.of("parse-email", "cell:\n  - parse-email: { column: c, lower: true }\n"),
        Arguments.of("parse-email bare", "cell:\n  - parse-email:\n      column: c\n      lower:\n"));
  }

  /** #1441: the docs' examples of the hooks that now refuse a list or a map stay valid — text, numbers, booleans. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("documentedConstants")
  void the_documented_constants_stay_valid(String hook, String tierBlock) {
    String yaml = "id: t\nversion: 1\ntarget: x\n" + tierBlock;
    assertThatCode(() -> transform(yaml)).as(hook).doesNotThrowAnyException();
  }

  static Stream<Arguments> documentedConstants() {
    return Stream.of(
        Arguments.of("enrich-context", "dataset:\n  - enrich-context: { source_system: siebel, ingested_at: \"${now}\" }\n"),
        Arguments.of("defaults", "row:\n  - defaults: { is_active: \"Y\", country: FR }\n"),
        Arguments.of("default", "cell:\n  - default: { column: legal_form, value: \"NONE\" }\n"),
        Arguments.of("lookup", "cell:\n  - lookup: { column: is_active, table: { \"Y\": true, \"N\": false }, on-missing: null }\n"),
        Arguments.of("regex-extract",
            "cell:\n  - regex-extract:\n      column: name\n      pattern: \".*\\\\b(SARL|SAS|EURL|SCI)\\\\b.*\"\n"
                + "      groups: { 1: legal_form }\n"),
        Arguments.of("aggregator",
            "row:\n  - aggregator:\n      emit-on-change: k\n      keep-first: [k]\n"
                + "      merge:\n        tags: { collect: t, into: csv, sep: '|', dedup: true }\n"),
        Arguments.of("filter-where in", "dataset:\n  - filter-where: { column: status, in: [ACTIVE, PENDING] }\n"),
        Arguments.of("filter-where equals", "dataset:\n  - filter-where: { column: status, equals: ACTIVE }\n"),
        Arguments.of("numeric column name", "cell:\n  - truncate: { column: 2024, length: 5 }\n"),
        // #1472: the docs' examples of the hooks whose column names are now read typed.
        Arguments.of("sort-by", "dataset:\n  - sort-by: { columns: [c_bpartner_id] }\n"),
        Arguments.of("require-headers",
            "dataset:\n  - require-headers: { columns: [c_bpartner_id, name], any-of: [created, isactive] }\n"),
        Arguments.of("coalesce", "row:\n  - coalesce: { column: display_name, from: [trade_name, name] }\n"),
        Arguments.of("validate-row", "row:\n  - validate-row: { required: [code, name] }\n"),
        Arguments.of("split-row by", "row:\n  - split-row: { by: items, sep: \"|\", position: item_no }\n"),
        Arguments.of("split-row explode", "row:\n  - split-row: { explode: items }\n"),
        Arguments.of("clean", "cell:\n  - clean: { columns: [name, note], strip: true, normalize-unicode: NFC }\n"),
        Arguments.of("clean all", "cell:\n  - clean: { columns: [\"*\"] }\n"),
        Arguments.of("parse-date",
            "cell:\n  - parse-date: { column: signup_date, formats: [\"dd/MM/yyyy\", \"yyyy-MM-dd HH:mm:ss\"] }\n"),
        Arguments.of("split", "cell:\n  - split: { column: tags, by: \";\", into: [tag1, tag2, tag3] }\n"),
        // #1482: the closed sets keep every word the hooks read, and value lists keep a blank value.
        Arguments.of("parse-number comma", "cell:\n  - parse-number: { column: balance, decimal-separator: \",\" }\n"),
        Arguments.of("parse-number dot", "cell:\n  - parse-number: { column: amount_eur, decimal-separator: \".\" }\n"),
        Arguments.of("parse-number auto", "cell:\n  - parse-number: { column: amount_eur, decimal-separator: auto }\n"),
        Arguments.of("clean NFKD", "cell:\n  - clean: { normalize-unicode: NFKD }\n"),
        Arguments.of("aggregator into array and set",
            "row:\n  - aggregator:\n      emit-on-change: k\n"
                + "      merge:\n        a: { collect: t, into: array }\n        b: { collect: t, into: set }\n"),
        Arguments.of("parse-date zone",
            "cell:\n  - parse-date: { column: d, formats: [\"yyyy-MM-dd HH:mm:ss\"], zone: Europe/Paris }\n"),
        Arguments.of("filter-where blank in value", "dataset:\n  - filter-where: { column: status, in: [\"\", ACTIVE] }\n"));
  }

  /** #1485: what was still refused only at run time — or never — is refused at validate, naming the key. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("runTimeRefusalsAtValidate")
  void a_run_time_refusal_is_refused_at_validate(String site, String tierBlock, String fragment) {
    String yaml = "id: t\nversion: 1\ntarget: x\n" + tierBlock;
    assertThatThrownBy(() -> transform(yaml))
        .as("%s must be refused at validate, before any row", site)
        .isInstanceOf(MappingValidationException.class)
        .hasMessageContaining(fragment);
  }

  static Stream<Arguments> runTimeRefusalsAtValidate() {
    return Stream.of(
        // The hooks that share clean's `columns` read it typed, as clean does.
        Arguments.of("fix-mojibake scalar columns", "cell:\n  - fix-mojibake: { columns: name }\n",
            "fix-mojibake hook: 'columns' must be a list of text, numbers or booleans (got string)"),
        Arguments.of("upper map columns", "cell:\n  - upper: { columns: { a: b } }\n",
            "upper hook: 'columns' must be a list of text, numbers or booleans (got object)"),
        Arguments.of("lower empty columns", "cell:\n  - lower: { columns: [] }\n",
            "lower hook: 'columns' must list at least one value (got an empty list)"),
        Arguments.of("titlecase empty element", "cell:\n  - titlecase: { columns: [\"\"] }\n",
            "titlecase hook: 'columns[0]' must name a column (got blank text)"),
        Arguments.of("strip-invisible bare columns", "cell:\n  - strip-invisible:\n      columns:\n",
            "strip-invisible hook: 'columns' must be a list of text, numbers or booleans (got null)"),
        Arguments.of("remove-diacritics blank element", "cell:\n  - remove-diacritics: { columns: [\"   \"] }\n",
            "remove-diacritics hook: 'columns[0]' must name a column (got blank text)"),
        Arguments.of("decode-html-entities list element", "cell:\n  - decode-html-entities: { columns: [a, [b]] }\n",
            "decode-html-entities hook: 'columns[1]' must be text, a number or a boolean (got array)"),
        Arguments.of("replace-smart-quotes blank element",
            "cell:\n  - replace-smart-quotes: { columns: [a, \"\"] }\n",
            "replace-smart-quotes hook: 'columns[1]' must name a column (got blank text)"),
        // A pattern is compiled once, at validate; the refusal names its index and the fault, never the pattern.
        Arguments.of("parse-date blank format", "cell:\n  - parse-date: { column: d, formats: [yyyy, \"  \"] }\n",
            "parse-date hook: 'formats[1]' is not a valid date pattern (blank text)"),
        Arguments.of("parse-date reserved character",
            "cell:\n  - parse-date: { column: d, formats: [\"yyyy-MM-dd{\"] }\n",
            "parse-date hook: 'formats[0]' is not a valid date pattern (a reserved character: {, } or #)"),
        Arguments.of("parse-date unknown letter", "cell:\n  - parse-date: { column: d, formats: [yyyy, bbbb] }\n",
            "parse-date hook: 'formats[1]' is not a valid date pattern (an unknown pattern letter)"),
        Arguments.of("parse-date unclosed quote", "cell:\n  - parse-date: { column: d, formats: [\"yyyy 'at\"] }\n",
            "parse-date hook: 'formats[0]' is not a valid date pattern (an unclosed quote)"),
        // The catalog's `column:` rule: empty or blank text names no column, in every tier.
        Arguments.of("cell empty column", "cell:\n  - truncate: { column: \"\", length: 5 }\n",
            "truncate hook: 'column' must name a column (got blank text)"),
        Arguments.of("row blank column", "row:\n  - coalesce: { column: \"   \", from: [a] }\n",
            "coalesce hook: 'column' must name a column (got blank text)"),
        Arguments.of("dataset empty column", "dataset:\n  - filter-where: { column: \"\", equals: x }\n",
            "filter-where hook: 'column' must name a column (got blank text)"),
        // An empty separator splits a value into its characters.
        Arguments.of("split empty by", "cell:\n  - split: { column: c, by: \"\", into: [a, b] }\n",
            "split hook: 'by' must not be empty"),
        Arguments.of("split-row empty sep", "row:\n  - split-row: { by: items, sep: \"\" }\n",
            "split-row hook: 'sep' must not be empty"));
  }

  @Test
  void a_malformed_date_pattern_is_never_quoted() {
    assertThatThrownBy(() -> transform("id: t\nversion: 1\ntarget: x\n"
        + "cell:\n  - parse-date: { column: d, formats: [\"qqqq-bxyzzy\"] }\n"))
        .isInstanceOf(MappingValidationException.class)
        .hasMessageContaining("'formats[0]'")
        .hasMessageNotContaining("xyzzy")
        .hasMessageNotContaining("qqqq");
  }

  /** #1485: the documented forms of the touched sites stay valid — `columns` absent or `["*"]`, a space separator. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("runTimeRefusalsKeepTheirValidForms")
  void the_valid_forms_stay_valid_at_validate(String site, String tierBlock) {
    String yaml = "id: t\nversion: 1\ntarget: x\n" + tierBlock;
    assertThatCode(() -> transform(yaml)).as(site).doesNotThrowAnyException();
  }

  static Stream<Arguments> runTimeRefusalsKeepTheirValidForms() {
    return Stream.of(
        Arguments.of("fix-mojibake all", "cell:\n  - fix-mojibake: { columns: [\"*\"] }\n"),
        Arguments.of("upper absent columns", "cell:\n  - upper: {}\n"),
        Arguments.of("lower listed", "cell:\n  - lower: { columns: [email] }\n"),
        Arguments.of("titlecase listed", "cell:\n  - titlecase: { columns: [name, city] }\n"),
        Arguments.of("strip-invisible all", "cell:\n  - strip-invisible: { columns: [\"*\"] }\n"),
        Arguments.of("remove-diacritics listed", "cell:\n  - remove-diacritics: { columns: [name] }\n"),
        Arguments.of("decode-html-entities listed", "cell:\n  - decode-html-entities: { columns: [description] }\n"),
        Arguments.of("replace-smart-quotes absent columns", "cell:\n  - replace-smart-quotes: {}\n"),
        Arguments.of("parse-date optional section and literal",
            "cell:\n  - parse-date: { column: d, formats: [\"yyyy-MM-dd['T'HH:mm[:ss]]\", \"dd 'de' MMMM yyyy\"] }\n"),
        Arguments.of("split space by", "cell:\n  - split: { column: c, by: \" \", into: [a, b] }\n"),
        Arguments.of("split-row space sep", "row:\n  - split-row: { by: items, sep: \" \" }\n"),
        Arguments.of("split-row bare sep", "row:\n  - split-row:\n      by: items\n      sep:\n"));
  }

  @Test
  void filter_where_keeps_what_it_kept() {
    List<Map<String, Object>> rows = List.of(
        new HashMap<>(Map.of("c", "ACTIVE")), new HashMap<>(Map.of("c", "0")), new HashMap<>(Map.of("c", "X")));
    MappingSpec in = MappingLoader.load("id: t\nversion: 1\ntarget: x\n"
        + "dataset:\n  - filter-where: { column: c, in: [ACTIVE, 0] }\n");
    assertThat(engine.transform(in, rows, new MappingState(), false)).extracting(r -> r.get("c"))
        .containsExactly("ACTIVE", "0");
    MappingSpec equals = MappingLoader.load("id: t\nversion: 1\ntarget: x\n"
        + "dataset:\n  - filter-where: { column: c, equals: 0 }\n");
    assertThat(engine.transform(equals, rows, new MappingState(), false)).extracting(r -> r.get("c"))
        .containsExactly("0");
  }

  @Test
  void a_number_or_a_boolean_keeps_its_text_form() {
    var out = transformRow("row:\n  - defaults: { qty: 0, active: true }\n", new MappingState(), "x");
    assertThat(out.get(0)).containsEntry("qty", "0").containsEntry("active", "true");
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
