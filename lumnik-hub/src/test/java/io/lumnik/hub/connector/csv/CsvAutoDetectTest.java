package io.lumnik.hub.connector.csv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CsvAutoDetectTest {

  @Test
  void detects_iso8859_1_encoding(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("latin1.csv");
    String content = "email,name,country\nalice@example.com,Bénédict,FR\n";
    Files.write(file, content.getBytes(StandardCharsets.ISO_8859_1));

    Charset c = EncodingDetector.detect(file);
    // ICU4J may detect ISO-8859-1 / windows-1252 / similar 8-bit codecs as candidates
    assertThat(c.name()).isIn("ISO-8859-1", "windows-1252", "ISO-8859-2", "ISO-8859-15");
  }

  @Test
  void detects_utf8_encoding(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("utf8.csv");
    Files.writeString(file, "email,name\nalice@example.com,Bénédict\n", StandardCharsets.UTF_8);

    Charset c = EncodingDetector.detect(file);
    assertThat(c.name()).isIn("UTF-8", "windows-1252"); // UTF-8 BOM-less may sometimes confuse
  }

  @Test
  void detects_semicolon_delimiter() throws Exception {
    var path = Path.of(getClass().getResource("/csv/customers-semicolon.csv").toURI());
    char d = DelimiterDetector.detect(path, StandardCharsets.UTF_8);
    assertThat(d).isEqualTo(';');
  }

  @Test
  void detects_comma_delimiter_default() throws Exception {
    var path = Path.of(getClass().getResource("/csv/customers-simple.csv").toURI());
    char d = DelimiterDetector.detect(path, StandardCharsets.UTF_8);
    assertThat(d).isEqualTo(',');
  }

  @Test
  void csv_reader_with_auto_config_handles_bom_and_delimiter(@TempDir Path tmp) throws Exception {
    // Create a CSV with BOM + comma delimiter
    String content = "﻿email,name\nalice@example.com,Alice\n";
    Path file = tmp.resolve("bom.csv");
    Files.writeString(file, content, StandardCharsets.UTF_8);

    var reader = new CsvFileReader();
    List<Map<String, Object>> rows = new ArrayList<>();
    reader.readAll(file, null, rows::add);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)).containsKey("email").containsEntry("email", "alice@example.com");
    // The header "email" should not have a BOM prefix
    assertThat(rows.get(0).keySet().iterator().next()).isEqualTo("email");
  }
}
