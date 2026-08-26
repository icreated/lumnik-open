package io.lumnik.hub.connector.csv;

import io.lumnik.hub.connector.csv.CsvFileConnectorType.ParserOverrides;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CsvConfigWiringTest {

  @Test
  void empty_parser_leaves_delimiter_and_encoding_for_autodetect() {
    ParserOverrides o = CsvFileConnectorType.buildParserOverrides(Map.of());
    assertThat(o.delimiter()).isNull();          // ← auto-detect
    assertThat(o.encoding()).isNull();           // ← auto-detect
    assertThat(o.hasHeader()).isTrue();
    assertThat(o.bomStrip()).isTrue();
    assertThat(o.nullValues()).contains("NULL");
    assertThat(o.columns()).isNull();
  }

  @Test
  void explicit_parser_config_is_captured() {
    ParserOverrides o = CsvFileConnectorType.buildParserOverrides(Map.of(
        "delimiter", ";",
        "encoding", "ISO-8859-1",
        "has_header", false,
        "columns", List.of("id", "label"),
        "null_values", List.of("", "N/A")));
    assertThat(o.delimiter()).isEqualTo(';');
    assertThat(o.encoding()).isEqualTo(StandardCharsets.ISO_8859_1);
    assertThat(o.hasHeader()).isFalse();
    assertThat(o.columns()).containsExactly("id", "label");
    assertThat(o.nullValues()).contains("N/A");
  }

  /** The regression this fix targets: with delimiter+encoding unset, the other parser fields
   *  (here null_values) must STILL be honored while encoding+delimiter are auto-detected. */
  @Test
  void effectiveConfig_autodetects_delimiter_while_honoring_other_overrides(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("semi.csv");
    Files.writeString(file, "a;b;c\n1;2;3\n", StandardCharsets.UTF_8);   // semicolon-delimited

    ParserOverrides o = CsvFileConnectorType.buildParserOverrides(Map.of(
        "null_values", List.of("X")));            // only null_values set; delimiter/encoding unset
    CsvFileReader.Config eff = CsvFileConnectorType.effectiveConfig(file, o);

    assertThat(eff.delimiter()).isEqualTo(';');   // auto-detected, NOT silently dropped to comma
    assertThat(eff.nullValues()).containsExactly("X");   // the override is honored
    assertThat(eff.hasHeader()).isTrue();
  }

  @Test
  void effectiveConfig_explicit_delimiter_overrides_autodetect(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("semi.csv");
    Files.writeString(file, "a;b\n1;2\n", StandardCharsets.UTF_8);       // looks semicolon

    ParserOverrides o = CsvFileConnectorType.buildParserOverrides(Map.of("delimiter", ","));
    CsvFileReader.Config eff = CsvFileConnectorType.effectiveConfig(file, o);

    assertThat(eff.delimiter()).isEqualTo(',');   // explicit override wins over auto-detect
  }
}
