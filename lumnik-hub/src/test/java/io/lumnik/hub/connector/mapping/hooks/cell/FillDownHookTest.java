package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** #1452: a value carried onto the rows that follow, reset at a marker, one state per declaration. */
class FillDownHookTest {

  private static final ObjectMapper M = new ObjectMapper();
  private final FillDownHook hook = new FillDownHook();

  /** Runs the hook over {@code rows} in order on one state, the way the engine does for one declaration. */
  private List<Map<String, Object>> run(String params, List<Map<String, Object>> rows) throws Exception {
    JsonNode p = M.readTree(params);
    hook.validateParams(p);
    MappingState state = new MappingState();
    state.atCellHook(0);
    for (Map<String, Object> row : rows) hook.apply(row, p, state);
    return rows;
  }

  private static Map<String, Object> row(Object... kv) {
    Map<String, Object> r = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) r.put((String) kv[i], kv[i + 1]);
    return r;
  }

  /** EDIFACT: the LIN's EAN reaches the QTY after it; a second LIN replaces it. */
  @Test
  void a_value_reaches_the_rows_that_follow_until_the_next_one() throws Exception {
    var rows = run("{\"columns\":[\"ean\"]}", new ArrayList<>(List.of(
        row("segment", "LIN", "ean", "3012345000110"),
        row("segment", "QTY", "ean", null),
        row("segment", "LIN", "ean", "3012345000127"),
        row("segment", "QTY", "ean", ""))));

    assertThat(rows).extracting(r -> r.get("ean"))
        .containsExactly("3012345000110", "3012345000110", "3012345000127", "3012345000127");
  }

  /** reset-when empties before the row's own value is captured: a LIN without EAN does not keep the previous one. */
  @Test
  void reset_when_empties_before_capture() throws Exception {
    var rows = run("{\"columns\":[\"ean\"],\"reset-when\":{\"column\":\"segment\",\"in\":[\"LIN\",\"UNT\"]}}",
        new ArrayList<>(List.of(
            row("segment", "LIN", "ean", "3012345000110"),
            row("segment", "QTY", "ean", null),
            row("segment", "LIN", "ean", null),
            row("segment", "QTY", "ean", null))));

    assertThat(rows).extracting(r -> r.get("ean")).containsExactly("3012345000110", "3012345000110", null, null);
  }

  /** Several columns, each carried on its own; nothing carried stays empty; the type is kept. */
  @Test
  void each_column_is_carried_on_its_own_and_keeps_its_type() throws Exception {
    LocalDate d = LocalDate.of(2026, 10, 22);
    var rows = run("{\"columns\":[\"order\",\"day\"]}", new ArrayList<>(List.of(
        row("order", null, "day", null),
        row("order", "PO-7001", "day", null),
        row("order", null, "day", d),
        row("order", "  ", "day", null))));

    assertThat(rows).extracting(r -> r.get("order")).containsExactly(null, "PO-7001", "PO-7001", "PO-7001");
    assertThat(rows).extracting(r -> r.get("day")).containsExactly(null, null, d, d);
  }

  /** A declared column missing from the row: nothing before the hook produces it — refused by name. */
  @Test
  void a_column_missing_from_the_row_is_refused_by_name() {
    assertThatThrownBy(() -> run("{\"columns\":[\"ean\"]}", new ArrayList<>(List.of(row("segment", "LIN")))))
        .isInstanceOf(MappingValidationException.class).hasMessageContaining("'ean'");
    assertThatThrownBy(() -> run("{\"columns\":[\"ean\"],\"reset-when\":{\"column\":\"tag\",\"in\":[\"LIN\"]}}",
        new ArrayList<>(List.of(row("ean", "x")))))
        .isInstanceOf(MappingValidationException.class).hasMessageContaining("'tag'");
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
      "{}                                                            | 'columns'",
      "{\"columns\":[]}                                              | at least one",
      "{\"columns\":[\" \"]}                                         | columns[0]",
      "{\"columns\":\"ean\"}                                         | 'columns'",
      "{\"columns\":[\"ean\"],\"reset-when\":\"LIN\"}                | 'reset-when'",
      "{\"columns\":[\"ean\"],\"reset-when\":{\"in\":[\"LIN\"]}}     | reset-when.column",
      "{\"columns\":[\"ean\"],\"reset-when\":{\"column\":\"s\"}}     | reset-when.in",
      "{\"columns\":[\"ean\"],\"reset-when\":{\"column\":\"s\",\"in\":[]}} | at least one",
      "{\"columns\":[\"ean\"],\"reset-when\":{\"column\":\"s\",\"in\":[\"LIN\"],\"on\":1}} | reset-when.on"})
  void a_malformed_declaration_is_refused(String params, String named) {
    assertThatThrownBy(() -> hook.validateParams(M.readTree(params)))
        .isInstanceOf(MappingValidationException.class).hasMessageContaining(named);
  }

  @Test
  void it_carries_state() {
    assertThat(hook.carriesState()).isTrue();
  }
}
