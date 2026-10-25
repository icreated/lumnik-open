package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #1338: {@code parse-number} with {@code integer: true} gives a CSV an integer column. The value is
 * converted exactly ({@code BigDecimal.longValueExact()}), never truncated nor sent through a double;
 * a value that is not a whole number within BIGINT is emptied and counted, as any parse-number failure. That a Long
 * makes a BIGINT column is SchemaTypingInventoryTest's, end to end ParseNumberIntegerLandsAsBigintIT's.
 */
class ParseNumberIntegerTest {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
  private static final String INTEGER = "column: id\ninteger: true";
  private final ParseNumberHook hook = new ParseNumberHook();

  private Object parse(Object value, String yaml) throws Exception {
    return parse(value, yaml, new MappingState());
  }

  private Object parse(Object value, String yaml, MappingState state) throws Exception {
    JsonNode p = YAML.readTree(yaml);
    hook.validateParams(p);
    Map<String, Object> row = new HashMap<>();
    row.put("id", value);
    hook.apply(row, p, state);
    return row.get("id");
  }

  @ParameterizedTest(name = "[{index}] {0} -> {1}")
  @CsvSource(delimiter = '|', value = {
      "7               | 7",
      "7.0             | 7",
      "7.0000          | 7",
      "' 1 024 944 '   | 1024944",
      "-42             | -42",
      "9223372036854775807  | 9223372036854775807",
      "-9223372036854775808 | -9223372036854775808",
  })
  void a_whole_number_becomes_a_long(String in, long expected) throws Exception {
    MappingState state = new MappingState();
    Object out = parse(in, INTEGER, state);
    assertThat(out).isInstanceOf(Long.class).isEqualTo(expected);
    assertThat(state.counters().cellErrors().get()).isZero();
  }

  @Test
  void separators_and_parentheses_follow_the_existing_rules() throws Exception {
    assertThat(parse("1.234,00", "column: id\ninteger: true\ndecimal-separator: ','"))
        .isEqualTo(1234L);
    assertThat(parse("(12)", "column: id\ninteger: true\nnegatives-in-parens: true"))
        .isEqualTo(-12L);
  }

  @ParameterizedTest(name = "[{index}] {0} is emptied and counted")
  @ValueSource(strings = {"7.1", "0.5", "9223372036854775808", "-9223372036854775809", "abc", "1e400"})
  void a_value_that_is_not_a_bigint_is_emptied_and_counted(String in) throws Exception {
    MappingState state = new MappingState();
    assertThat(parse(in, INTEGER, state)).isNull();
    assertThat(state.counters().cellErrors().get()).isEqualTo(1);
  }

  @Test
  void a_number_that_arrives_already_typed_is_held_to_the_same_rule() throws Exception {
    assertThat(parse(new BigDecimal("7.000"), INTEGER)).isEqualTo(7L);
    assertThat(parse(7, INTEGER)).isEqualTo(7L);
    MappingState state = new MappingState();
    assertThat(parse(new BigDecimal("7.5"), INTEGER, state)).isNull();
    assertThat(state.counters().cellErrors().get()).isEqualTo(1);
  }

  /** A double that is not a number at all — NaN, an infinity — is emptied and counted, never a crash of the chunk. */
  @Test
  void a_double_that_is_not_a_finite_number_is_emptied_and_counted() throws Exception {
    for (Object notFinite : new Object[]{Double.NaN, Double.POSITIVE_INFINITY}) {
      MappingState state = new MappingState();
      assertThat(parse(notFinite, INTEGER, state)).isNull();
      assertThat(state.counters().cellErrors().get()).isEqualTo(1);
    }
  }

  @Test
  void without_the_option_nothing_changes() throws Exception {
    Object out = parse("7", "column: id");
    assertThat(out).isInstanceOf(BigDecimal.class).hasToString("7");
    assertThat(parse(new BigDecimal("7.5"), "column: id")).isEqualTo(new BigDecimal("7.5"));
    assertThat(parse("7", "column: id\ninteger: false")).isInstanceOf(BigDecimal.class);
  }

  @ParameterizedTest(name = "[{index}] integer: {0} is refused")
  @ValueSource(strings = {"'yes'", "'true'", "1", "[true]"})
  void an_integer_option_that_is_not_a_boolean_is_refused(String value) {
    assertThatThrownBy(() -> hook.validateParams(YAML.readTree("column: id\ninteger: " + value)))
        .isInstanceOf(MappingValidationException.class)
        .hasMessageContaining("'integer' must be true or false");
  }
}
