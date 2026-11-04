package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1762: a CSV's empty cell arrives blank, not null. Every parse-* hook reads it as empty — the cell stays empty and
 * nothing is counted — where four of them used to count it in cell_errors as a value they could not read.
 */
class BlankCellParseHooksTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  static Stream<Arguments> hooks() {
    return Stream.of(
        Arguments.of(new ParseDateHook(), "{\"column\":\"c\",\"formats\":[\"yyyyMMdd\"]}"),
        Arguments.of(new ParseNumberHook(), "{\"column\":\"c\"}"),
        Arguments.of(new ParseEmailHook(), "{\"column\":\"c\"}"),
        Arguments.of(new ParsePhoneHook(), "{\"column\":\"c\",\"default-region\":\"FR\"}"),
        Arguments.of(new ParseBooleanHook(), "{\"column\":\"c\"}"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("hooks")
  void a_blank_cell_stays_empty_and_is_not_counted(CellHook hook, String params) throws Exception {
    JsonNode p = JSON.readTree(params);
    hook.validateParams(p);
    MappingState state = new MappingState();
    for (String blank : new String[] {"", "   ", "\t"}) {
      Map<String, Object> row = new HashMap<>();
      row.put("c", blank);
      hook.apply(row, p, state);
      assertThat(row.get("c")).as("'%s'", blank).isNull();
    }
    assertThat(state.counters().cellErrors().get()).isZero();
  }
}
