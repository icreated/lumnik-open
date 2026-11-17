package io.lumnik.hub.connector.mapping;

import io.lumnik.hub.connector.api.types.SkipReason;
import io.lumnik.hub.connector.mapping.spec.MappingLoader;
import io.lumnik.hub.connector.mapping.spec.MappingSpec;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #1408: {@code on-error} decides what a value a cell hook cannot deliver does to its row — {@code soft} writes the
 * row with the cell emptied (the default), {@code dlq} dead-letters the row as it was read, {@code fail} stops.
 */
@QuarkusTest
class CellErrorPolicyTest {

  @Inject MappingEngine engine;

  private static MappingSpec spec(String onError) {
    return MappingLoader.load("""
        id: cell-error-policy
        version: 1
        target: x
        cell:
          - { parse-number: { column: amount, decimal-separator: "." } }
        """ + onError);
  }

  private static List<Map<String, Object>> invoices() {
    return List.of(
        new HashMap<>(Map.of("invoice", "FA-001", "amount", "412.50")),
        new HashMap<>(Map.of("invoice", "FA-002", "amount", "1.250.00")));
  }

  @Test
  void without_on_error_the_row_is_written_with_the_cell_emptied() {
    List<Map<String, Object>> rows = engine.transform(spec(""), invoices(), new MappingState(), false);

    assertThat(rows).hasSize(2);
    assertThat(rows.get(1).get("amount")).isNull();
  }

  @Test
  void dlq_on_the_column_dead_letters_the_row_as_it_was_read_and_writes_the_others() {
    MappingState state = new MappingState();

    List<Map<String, Object>> rows =
        engine.transform(spec("on-error: { fields: { amount: dlq } }"), invoices(), state, false);

    assertThat(rows).extracting(r -> r.get("invoice")).containsExactly("FA-001");
    assertThat(state.dropped()).singleElement().satisfies(d -> {
      assertThat(d.row()).containsEntry("invoice", "FA-002").containsEntry("amount", "1.250.00");
      assertThat(d.reason()).isEqualTo(new SkipReason.MappingFailed("amount", "parse-number could not read the value"));
    });
    // The cell count says what was written emptied: this row was not written.
    assertThat(state.counters().cellErrors()).hasValue(0);
  }

  @Test
  void the_default_mode_covers_a_column_the_fields_do_not_name() {
    MappingState state = new MappingState();

    engine.transform(spec("on-error: { default: dlq }"), invoices(), state, false);

    assertThat(state.dropped()).hasSize(1);
  }

  @Test
  void dlq_on_another_column_leaves_this_one_soft() {
    MappingState state = new MappingState();

    List<Map<String, Object>> rows =
        engine.transform(spec("on-error: { fields: { invoice: dlq } }"), invoices(), state, false);

    assertThat(rows).hasSize(2);
    assertThat(state.dropped()).isEmpty();
    assertThat(state.counters().cellErrors()).hasValue(1);
  }

  @Test
  void fail_stops_the_chunk_naming_the_column_and_the_hook() {
    assertThatThrownBy(() ->
        engine.transform(spec("on-error: { fields: { amount: fail } }"), invoices(), new MappingState(), false))
        .isInstanceOf(MappingValidationException.class)
        .hasMessageContaining("amount")
        .hasMessageContaining("parse-number");
  }

  /**
   * Review of #1409: split-row fans one source row into fragments, and dlq would drop each unreadable fragment —
   * two drops for one source row. The write refuses the chunk rather than count records_out negative; the
   * validator refuses the composition before any run (CsvFileConnectorValidatorTest).
   */
  @Test
  void dlq_after_split_row_dropping_two_fragments_of_one_row_is_refused_by_the_write() {
    MappingSpec spec = MappingLoader.load("""
        id: cell-error-split
        version: 1
        target: x
        row:
          - { split-row: { by: amount, sep: "|" } }
        cell:
          - { parse-number: { column: amount, decimal-separator: "." } }
        on-error: { fields: { amount: dlq } }
        """);
    List<Map<String, Object>> input = List.of(new HashMap<>(Map.of("invoice", "FA-002", "amount", "x|y")));

    assertThatThrownBy(() -> engine.transformAndWrite(spec, input, 1L, UUID.randomUUID(), false, null, List.of()))
        .isInstanceOf(MappingValidationException.class)
        .hasMessageContaining("2 drops for 1 source rows");
  }

  @Test
  void fail_on_an_every_column_hook_names_the_hook_without_an_empty_column() {
    MappingSpec spec = MappingLoader.load("""
        id: cell-error-every-column
        version: 1
        target: x
        cell:
          - { fix-mojibake: {} }
        on-error: { default: fail }
        """);
    // Mojibake beside a sign above U+00FF: the hook declines and counts.
    List<Map<String, Object>> input = List.of(new HashMap<>(Map.of("name", "BÃ©nÃ©dict €")));

    assertThatThrownBy(() -> engine.transform(spec, input, new MappingState(), false))
        .isInstanceOf(MappingValidationException.class)
        .hasMessageContaining("fix-mojibake")
        .hasMessageNotContaining("''");
  }

  /** #1417: a required regex-extract that misses is a cell not delivered, so on-error dead-letters its row. */
  @Test
  void a_required_regex_extract_miss_dead_letters_the_row_under_dlq() {
    MappingSpec spec = MappingLoader.load("""
        id: cell-error-regex
        version: 1
        target: x
        cell:
          - { regex-extract: { column: url, pattern: "^(https?)://([^/:]+).*$", groups: { 2: server }, required: true } }
        on-error: { fields: { url: dlq } }
        """);
    List<Map<String, Object>> input = List.of(
        new HashMap<>(Map.of("app", "ERP", "url", "https://srv-erp-prd01/erp")),
        new HashMap<>(Map.of("app", "EDI", "url", "ftp://srv-edi-prd01:21")));
    MappingState state = new MappingState();

    List<Map<String, Object>> rows = engine.transform(spec, input, state, false);

    assertThat(rows).extracting(r -> r.get("app")).containsExactly("ERP");
    assertThat(state.dropped()).singleElement().satisfies(d -> {
      assertThat(d.row()).containsEntry("url", "ftp://srv-edi-prd01:21");
      assertThat(d.reason()).isEqualTo(new SkipReason.MappingFailed("url", "regex-extract could not read the value: the pattern did not match"));
    });
  }

  /**
   * #1432: an operator script that cannot read a value says so with {@code unreadable}, and on-error decides the row
   * like for a built-in hook — the {@code kotlin} hook has no {@code column:}, so {@code default} applies.
   */
  private static MappingSpec quantities(String onError) {
    return MappingLoader.load("""
        id: cell-error-kotlin
        version: 1
        target: x
        cell:
          - { kotlin: { script: "classpath:mapping/read-quantity.kts" } }
        """ + onError);
  }

  private static List<Map<String, Object>> receipts() {
    return List.of(
        new HashMap<>(Map.of("line_no", "L0316", "quantity", "12,5 kg")),
        new HashMap<>(Map.of("line_no", "L0317", "quantity", "env. 4 kg")),
        new HashMap<>(Map.of("line_no", "L0318", "quantity", "3 pce")));
  }

  @Test
  void a_value_a_script_declares_unreadable_is_counted_and_its_row_written_by_default() {
    MappingState state = new MappingState();

    List<Map<String, Object>> rows = engine.transform(quantities(""), receipts(), state, false);

    assertThat(rows).extracting(r -> r.get("line_no")).containsExactly("L0316", "L0317", "L0318");
    assertThat(rows.get(0)).containsEntry("qty", "12.5").containsEntry("unit", "kg");
    assertThat(state.counters().cellErrors()).hasValue(1);
  }

  @Test
  void a_value_a_script_declares_unreadable_dead_letters_its_row_under_default_dlq() {
    MappingState state = new MappingState();

    List<Map<String, Object>> rows = engine.transform(quantities("on-error: { default: dlq }"), receipts(), state, false);

    assertThat(rows).extracting(r -> r.get("line_no")).containsExactly("L0316", "L0318");
    assertThat(state.dropped()).singleElement().satisfies(d -> {
      assertThat(d.row()).containsEntry("line_no", "L0317").containsEntry("quantity", "env. 4 kg");
      assertThat(d.reason()).isEqualTo(new SkipReason.MappingFailed("kotlin", "kotlin could not read the value"));
    });
  }

  @Test
  void an_unknown_mode_is_refused_when_the_mapping_is_read() {
    assertThatThrownBy(() -> spec("on-error: { fields: { amount: quarantine } }"))
        .isInstanceOf(MappingValidationException.class)
        .hasMessageContaining("soft, dlq or fail");
  }

  // #1966: parse-coordinates reads a pair and has no `column`, so `default` decides its row; `fields` never does.

  private static MappingSpec sites(String onError) {
    return MappingLoader.load("""
        id: cell-error-policy-pair
        version: 1
        target: x
        cell:
          - { parse-coordinates: { lat: latitude, lon: longitude } }
        """ + onError);
  }

  private static List<Map<String, Object>> twoSites() {
    return List.of(
        new HashMap<>(Map.of("site", "S01", "latitude", "45.7302", "longitude", "4.8309")),
        new HashMap<>(Map.of("site", "S08", "latitude", "574357.82", "longitude", "6279642.97")));
  }

  @Test
  void an_unreadable_pair_is_written_emptied_and_counted_once_by_default() {
    MappingState state = new MappingState();

    List<Map<String, Object>> rows = engine.transform(sites(""), twoSites(), state, false);

    assertThat(rows).hasSize(2);
    assertThat(rows.get(1)).containsEntry("latitude", null).containsEntry("longitude", null);
    assertThat(state.counters().cellErrors()).hasValue(1);
  }

  @Test
  void default_dlq_dead_letters_the_site_as_it_was_read_named_by_the_hook_with_its_reason() {
    MappingState state = new MappingState();

    List<Map<String, Object>> rows = engine.transform(sites("on-error: { default: dlq }"), twoSites(), state, false);

    assertThat(rows).extracting(r -> r.get("site")).containsExactly("S01");
    assertThat(state.dropped()).singleElement().satisfies(d -> {
      assertThat(d.row()).containsEntry("latitude", "574357.82").containsEntry("longitude", "6279642.97");
      assertThat(d.reason()).isEqualTo(
          new SkipReason.MappingFailed("parse-coordinates", "parse-coordinates could not read the value: not degrees"));
    });
    assertThat(state.counters().cellErrors()).hasValue(0);
  }

  @Test
  void a_reason_given_by_one_hook_does_not_reach_the_next_hook_of_the_same_row() {
    MappingState state = new MappingState();
    MappingSpec spec = MappingLoader.load("""
        id: cell-error-policy-pair-then-number
        version: 1
        target: x
        on-error: { fields: { amount: dlq } }
        cell:
          - { parse-coordinates: { lat: latitude, lon: longitude } }
          - { parse-number: { column: amount } }
        """);
    List<Map<String, Object>> rows = List.of(
        new HashMap<>(Map.of("latitude", "574357.82", "longitude", "6279642.97", "amount", "n/a")));

    engine.transform(spec, rows, state, false);

    // The pair is refused under `soft` with a reason; the amount, under `dlq`, without one.
    assertThat(state.dropped()).extracting(MappingState.DroppedRow::reason).containsExactly(
        new SkipReason.MappingFailed("amount", "parse-number could not read the value"));
  }

  @Test
  void a_fields_entry_on_one_column_of_the_pair_does_not_decide_it() {
    MappingState state = new MappingState();

    List<Map<String, Object>> rows =
        engine.transform(sites("on-error: { fields: { latitude: dlq } }"), twoSites(), state, false);

    assertThat(rows).hasSize(2);
    assertThat(state.dropped()).isEmpty();
  }

  @Test
  void default_fail_stops_on_an_unreadable_pair() {
    assertThatThrownBy(() -> engine.transform(sites("on-error: { default: fail }"), twoSites(), new MappingState(), false))
        .isInstanceOf(MappingValidationException.class)
        .hasMessage("on-error: fail — parse-coordinates could not read a value: not degrees");
  }

  // #1971: a transformer that knows why it refused the value says it in the dead letter; the reason names the
  // structure, never the value.
  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', textBlock = """
      split: { column: v, by: /, into: [a, b] }                 | 1/2/3   | v                | split could not read the value: 3 parts for 2 names
      fix-mojibake: { columns: [v] }                            | Ã©日    | fix-mojibake     | fix-mojibake could not read the value: not Windows-1252 text in v
      fix-mojibake: { columns: [v] }                            | Ã©Ã    | fix-mojibake     | fix-mojibake could not read the value: not valid UTF-8 once repaired in v
      to-uuid: { column: v, on-invalid: null }                  | N/A     | v                | to-uuid could not read the value: not a UUID
      to-uuid: { column: id, generate-from: v }                 | ' '     | id               | to-uuid could not read the value: nothing to derive it from
      parse-date: { column: v, formats: [yyyy-MM-dd] }          | soon    | v                | parse-date could not read the value: none of the declared formats
      to-uuid: { column: v, on-invalid: generate, generate-from: w } | N/A | v                | to-uuid could not read the value: nothing to derive it from
      parse-number: { column: v }                               | n/a     | v                | parse-number could not read the value
      parse-boolean: { column: v }                              | maybe   | v                | parse-boolean could not read the value
      parse-email: { column: v }                                | nobody  | v                | parse-email could not read the value
      """)
  void a_dead_letter_says_why_the_transformer_refused_the_value(String hook, String value, String field, String message) {
    MappingSpec spec = MappingLoader.load("""
        id: cell-error-reason
        version: 1
        target: x
        on-error: { default: dlq }
        cell:
          - { %s }
        """.formatted(hook));
    MappingState state = new MappingState();

    engine.transform(spec, List.of(new HashMap<>(Map.of("v", value))), state, false);

    assertThat(state.dropped()).singleElement()
        .satisfies(d -> assertThat(d.reason()).isEqualTo(new SkipReason.MappingFailed(field, message)));
  }

  @Test
  void a_transformer_refusing_two_columns_names_the_first() {
    MappingSpec spec = MappingLoader.load("""
        id: cell-error-reason-two-columns
        version: 1
        target: x
        on-error: { default: dlq }
        cell:
          - { fix-mojibake: { columns: [a, b] } }
        """);
    MappingState state = new MappingState();

    engine.transform(spec, List.of(new HashMap<>(Map.of("a", "Ã©日", "b", "Ã©Ã"))), state, false);

    assertThat(state.dropped()).singleElement().satisfies(d -> assertThat(d.reason()).isEqualTo(
        new SkipReason.MappingFailed("fix-mojibake", "fix-mojibake could not read the value: not Windows-1252 text in a")));
  }

  @Test
  void default_fail_stops_with_the_reason() {
    MappingSpec spec = MappingLoader.load("""
        id: cell-error-reason-fail
        version: 1
        target: x
        on-error: { default: fail }
        cell:
          - { split: { column: v, by: /, into: [a, b] } }
        """);

    assertThatThrownBy(() -> engine.transform(spec, List.of(new HashMap<>(Map.of("v", "1/2/3"))), new MappingState(), false))
        .isInstanceOf(MappingValidationException.class)
        .hasMessage("on-error: fail — split could not read a value of column 'v': 3 parts for 2 names");
  }
}
