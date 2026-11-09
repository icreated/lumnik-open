package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.lumnik.hub.connector.mapping.MappingState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1878: the grouping separator Java writes for French — U+00A0, and U+202F since JDK 13 (CLDR) — is a space like the
 * ASCII one. {@code \s} without UNICODE_CHARACTER_CLASS does not match either, so every amount of 1,000 or more was lost.
 */
class ParseNumberGroupingSpaceTest {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
  private final ParseNumberHook hook = new ParseNumberHook();

  private Object parse(String value) throws Exception {
    JsonNode p = YAML.readTree("column: amount\ndecimal-separator: \",\"");
    hook.validateParams(p);
    Map<String, Object> row = new HashMap<>();
    row.put("amount", value);
    hook.apply(row, p, new MappingState());
    return row.get("amount");
  }

  @ParameterizedTest(name = "U+{0}")
  @ValueSource(strings = {"00A0", "2007", "202F"})
  void aNoBreakSpaceGroupsThousandsLikeAnAsciiOne(String codePoint) throws Exception {
    String space = new String(Character.toChars(Integer.parseInt(codePoint, 16)));
    assertThat(parse("2" + space + "310,00")).isEqualTo(new BigDecimal("2310.00"));
  }

  @Test
  void anAsciiSpaceStillGroups() throws Exception {
    assertThat(parse("1 234,50")).isEqualTo(new BigDecimal("1234.50"));
  }
}
