package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.HookCatalog;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1419: UTF-8 read back as <b>Windows-1252</b> — every Windows export — puts the second byte of {@code É}, {@code È},
 * {@code œ} and of every 3-byte character on a cp1252 sign above U+00FF ({@code ‰}, {@code ˆ}, {@code “}, {@code ™}…).
 * The hook used to see no mojibake there: the value was neither repaired nor counted, and {@code titlecase} then
 * lowercased the {@code Ã}. Replayed on a live hub with a CRM export: {@code CAFÃ‰ DU PORT} became
 * {@code Cafã‰ Du Port}, {@code cellErrors} 0.
 */
@QuarkusTest
class FixMojibakeCp1252Test {

  @Inject HookCatalog catalog;
  private final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource(delimiter = '|', value = {
      // the cp1252 reading, repaired
      "CAFÃ‰ DU PORT|CAFÉ DU PORT|0",
      "ATELIER LEFÃˆVRE|ATELIER LEFÈVRE|0",
      "Å“uvre|œuvre|0",
      "lâ€™atelier|l’atelier|0",
      "10 â‚¬|10 €|0",
      "2020â€“2024|2020–2024|0",
      // the Latin-1 reading, as before
      "BÃ©nÃ©dicte|Bénédicte|0",
      // clean text, cp1252 signs included, untouched and not counted
      "Café 10 € l’été – Œuvre|Café 10 € l’été – Œuvre|0",
      // a sign with no byte beside the mojibake: declined, counted
      "CAFÃ‰ 日本|CAFÃ‰ 日本|1"})
  void windows_1252_mojibake_is_repaired_like_latin_1(String input, String expected, long cellErrors)
      throws Exception {
    JsonNode params = YAML.readTree("columns: [name]\n");
    var row = new HashMap<String, Object>();
    row.put("name", input);
    var state = new MappingState();

    catalog.cell("fix-mojibake").apply(row, params, state);

    assertThat(row.get("name")).isEqualTo(expected);
    assertThat(state.counters().cellErrors().get()).isEqualTo(cellErrors);
  }
}
