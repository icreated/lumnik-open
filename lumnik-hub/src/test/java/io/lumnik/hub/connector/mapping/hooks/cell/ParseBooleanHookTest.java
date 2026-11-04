package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #1760: a legacy export writes its flags as 0/1, O/N, Oui/Non — {@code parse-boolean} makes them a Boolean, which
 * the writer types as a BOOLEAN column (SchemaTypingInventoryTest). A word in neither list is emptied and counted,
 * never guessed.
 */
class ParseBooleanHookTest {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
  private final ParseBooleanHook hook = new ParseBooleanHook();

  private Object parse(Object value, String yaml, MappingState state) throws Exception {
    JsonNode p = YAML.readTree(yaml);
    hook.validateParams(p);
    Map<String, Object> row = new HashMap<>();
    row.put("actif", value);
    hook.apply(row, p, state);
    return row.get("actif");
  }

  @ParameterizedTest(name = "[{index}] {0} -> {1}")
  @CsvSource(delimiter = '|', value = {
      "1      | true", "0     | false",
      "true   | true", "FALSE | false",
      "yes    | true", "No    | false",
      "Oui    | true", "non   | false",
      "O      | true", "N     | false",
      "y      | true", "n     | false",
      "T      | true", "f     | false",
      "vrai   | true", "Faux  | false",
      "' 1 '  | true",
  })
  void the_usual_words_are_read_whatever_their_case(String in, boolean expected) throws Exception {
    MappingState state = new MappingState();
    assertThat(parse(in, "column: actif", state)).isEqualTo(expected);
    assertThat(state.counters().cellErrors().get()).isZero();
  }

  @Test
  void a_word_in_neither_list_is_emptied_and_counted_never_guessed() throws Exception {
    MappingState state = new MappingState();
    assertThat(parse("2", "column: actif", state)).isNull();
    assertThat(parse("peut-être", "column: actif", state)).isNull();
    assertThat(state.counters().cellErrors().get()).isEqualTo(2);
  }

  @Test
  void an_empty_cell_stays_empty_and_a_boolean_stays_as_it_is() throws Exception {
    MappingState state = new MappingState();
    assertThat(parse(null, "column: actif", state)).isNull();
    assertThat(parse("   ", "column: actif", state)).isNull();   // review of #1761: a CSV's empty cell is blank, not null
    assertThat(parse("", "column: actif", state)).isNull();
    assertThat(parse(Boolean.FALSE, "column: actif", state)).isEqualTo(false);
    assertThat(state.counters().cellErrors().get()).isZero();
  }

  @Test
  void a_source_s_own_words_replace_the_defaults() throws Exception {
    MappingState state = new MappingState();
    String yaml = "column: actif\ntrue: [A, Actif]\nfalse: [I]";
    assertThat(parse("a", yaml, state)).isEqualTo(true);
    assertThat(parse("i", yaml, state)).isEqualTo(false);
    assertThat(parse("1", yaml, state)).isNull();   // the defaults are replaced, not added to
    assertThat(state.counters().cellErrors().get()).isEqualTo(1);
  }

  @Test
  void a_word_in_both_lists_is_refused() {
    assertThatThrownBy(() -> hook.validateParams(YAML.readTree("column: actif\ntrue: [O, 1]\nfalse: [N, o]")))
        .isInstanceOf(MappingValidationException.class)
        .hasMessage("parse-boolean hook: 'o' is in both true and false");
  }

  @Test
  void a_blank_word_is_refused_a_blank_cell_stays_empty() {
    // Review of #1761: a blank word would turn every blank cell into true or false.
    assertThatThrownBy(() -> hook.validateParams(YAML.readTree("column: actif\ntrue: [A, \" \"]")))
        .isInstanceOf(MappingValidationException.class)
        .hasMessage("parse-boolean hook: 'true' holds a blank word — a blank cell stays empty");
  }

  @Test
  void an_empty_list_is_refused() {
    assertThatThrownBy(() -> hook.validateParams(YAML.readTree("column: actif\ntrue: []")))
        .isInstanceOf(MappingValidationException.class)
        .hasMessageContaining("'true' must list at least one value");
  }
}
