package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.lumnik.hub.connector.mapping.MappingState;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The fixed regexes of parse-number and clean: each literal of the character class is pinned. */
class ConstantPatternHooksTest {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  private static Map<String, Object> run(CellHookRunner hook, String yaml, Object value) throws Exception {
    JsonNode params = YAML.readTree(yaml);
    Map<String, Object> row = new HashMap<>(Map.of("c", value));
    hook.apply(row, params, new MappingState());
    return row;
  }

  private interface CellHookRunner {
    void apply(Map<String, Object> row, JsonNode params, MappingState s);
  }

  @ParameterizedTest(name = "[{0}] is dropped before parsing")
  @ValueSource(strings = {"$1234", "€1234", "£1234", "¥1234", " 1234 ", "12 34"})
  void parseNumberDropsCurrencySymbolsAndSpaces(String cell) throws Exception {
    Object out = run(new ParseNumberHook()::apply, "column: c", cell).get("c");
    assertThat(new BigDecimal(out.toString())).isEqualByComparingTo("1234");
  }

  @ParameterizedTest(name = "control char U+{0}")
  @ValueSource(strings = {"0007", "000A", "001F", "007F"})
  void cleanRemovesControlCharactersOnlyWhenAsked(String hex) throws Exception {
    String cell = "a" + (char) Integer.parseInt(hex, 16) + "b";
    assertThat(run(new CleanHook()::apply, "columns: [c]\nremove-control-chars: true", cell).get("c")).isEqualTo("ab");
    assertThat(run(new CleanHook()::apply, "columns: [c]", cell).get("c")).isEqualTo(cell);
  }
}
