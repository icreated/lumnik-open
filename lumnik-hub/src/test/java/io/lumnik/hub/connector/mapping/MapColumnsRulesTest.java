package io.lumnik.hub.connector.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** The save-time check of the flat connector and endpoint doors, {@link MapColumnsRules#firstStoredConfigViolation}. */
class MapColumnsRulesTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * #1475: a tier written as a map (or a scalar) was skipped at save — only an array was read — and refused later by
   * the run (#1464). The door now refuses it with the run's own message.
   */
  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
      "dataset|{\"skip-empty-rows\": true}|object",
      "row|{\"map-columns\": {\"rename\": {\"a\": \"b\"}}}|object",
      "cell|\"upper\"|string"})
  void a_tier_that_is_not_a_list_is_refused(String tier, String json, String shape) throws Exception {
    var config = JSON.readTree("{\"mapping\": {\"" + tier + "\": " + json + "}}");

    assertThat(MapColumnsRules.firstStoredConfigViolation(config)).contains(new MapColumnsRules.Violation(
        "mapping." + tier,
        tier + " must be a list of hooks, e.g. - upper: { columns: [name] } (got " + shape + ")"));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "{}",
      "{\"mapping\": {}}",
      "{\"mapping\": {\"row\": null}}",
      "{\"mapping\": {\"row\": [{\"map-columns\": {\"rename\": {\"a\": \"b\"}}}]}}"})
  void an_absent_empty_or_listed_tier_is_accepted(String json) throws Exception {
    assertThat(MapColumnsRules.firstStoredConfigViolation(JSON.readTree(json))).isEmpty();
  }
}
