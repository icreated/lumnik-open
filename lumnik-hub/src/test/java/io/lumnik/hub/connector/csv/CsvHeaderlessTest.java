package io.lumnik.hub.connector.csv;

import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class CsvHeaderlessTest {

  @Test
  void headerless_uses_configured_columns_and_keeps_first_row_as_data(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("noheader.csv");
    Files.writeString(file, "1,alice@x.com\n2,bob@x.com\n", StandardCharsets.UTF_8);

    var cfg = new CsvFileReader.Config(
        StandardCharsets.UTF_8, ',', /*hasHeader*/ false, /*bomStrip*/ true,
        List.of("", "NULL"), /*columns*/ List.of("id", "email"));

    List<Map<String, Object>> rows = new ArrayList<>();
    new CsvFileReader().readAll(file, cfg, rows::add);

    assertThat(rows).hasSize(2);                                  // first row is DATA, not header
    assertThat(rows.get(0)).containsEntry("id", "1").containsEntry("email", "alice@x.com");
    assertThat(rows.get(1)).containsEntry("id", "2").containsEntry("email", "bob@x.com");
  }

  @Test
  void headerless_without_columns_fails_loudly(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("noheader.csv");
    Files.writeString(file, "1,alice@x.com\n", StandardCharsets.UTF_8);

    var cfg = new CsvFileReader.Config(
        StandardCharsets.UTF_8, ',', false, true, List.of(""), /*columns*/ null);

    assertThatThrownBy(() -> new CsvFileReader().readAll(file, cfg, r -> {}))
        .isInstanceOf(ConfigValidationException.class)
        .hasMessageContaining("has-header");
  }
}
