package io.lumnik.hub.connector.mapping;

import io.lumnik.hub.connector.api.types.SkipReason;
import io.lumnik.hub.connector.mapping.spec.MappingLoader;
import io.lumnik.hub.connector.mapping.spec.MappingSpec;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

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
      assertThat(d.reason()).isEqualTo(new SkipReason.MappingFailed("url", "regex-extract could not read the value"));
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
}
