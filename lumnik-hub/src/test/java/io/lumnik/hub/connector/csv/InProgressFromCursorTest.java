package io.lumnik.hub.connector.csv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a persisted {@code inProgress} block must carry to be trusted. Anything less fails LOUDLY — a
 * silent drop would restart a partially-ingested file, the one forbidden move on this path. Each clause of
 * the completeness check is pinned alone: every other field stays valid.
 */
class InProgressFromCursorTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static JsonNode cursor(String block) throws Exception {
    return JSON.readTree("{\"inProgress\":" + block + "}");
  }

  private static String block(String path, String size, String rows, String encoding, String lastModified,
                              String delimiter) {
    return "{\"path\":" + path + ",\"size\":" + size + ",\"rowsProcessed\":" + rows
        + ",\"encoding\":" + encoding + ",\"lastModified\":" + lastModified + ",\"delimiter\":" + delimiter + "}";
  }

  private static final String OK_PATH = "\"/in/a.csv\"";
  private static final String OK_ENC = "\"UTF-8\"";
  private static final String OK_TS = "\"2026-11-01T10:00:00Z\"";
  private static final String OK_DELIM = "\";\"";

  @Test
  void a_complete_block_is_read() throws Exception {
    var ip = FileSourceCursorReader.InProgress.fromCursor(cursor(block(OK_PATH, "10", "3", OK_ENC, OK_TS, OK_DELIM)));
    assertThat(ip.path()).isEqualTo("/in/a.csv");
    assertThat(ip.rowsProcessed()).isEqualTo(3);
    assertThat(ip.delimiter()).isEqualTo(";");
  }

  @Test
  void zero_size_and_zero_rows_are_complete() throws Exception {
    assertThat(FileSourceCursorReader.InProgress.fromCursor(cursor(block(OK_PATH, "0", "0", OK_ENC, OK_TS, OK_DELIM))))
        .isNotNull();
  }

  @Test
  void an_absent_or_null_block_is_no_block() throws Exception {
    assertThat(FileSourceCursorReader.InProgress.fromCursor(JSON.readTree("{}"))).isNull();
    assertThat(FileSourceCursorReader.InProgress.fromCursor(JSON.readTree("{\"inProgress\":null}"))).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "\"\"", "\"   \""})
  void a_missing_or_blank_path_is_refused(String path) throws Exception {
    refused(block(path, "10", "3", OK_ENC, OK_TS, OK_DELIM));
  }

  @Test
  void a_negative_size_is_refused() throws Exception {
    refused(block(OK_PATH, "-1", "3", OK_ENC, OK_TS, OK_DELIM));
  }

  @Test
  void a_negative_row_count_is_refused() throws Exception {
    refused(block(OK_PATH, "10", "-1", OK_ENC, OK_TS, OK_DELIM));
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "\"\"", "\";;\""})
  void a_missing_or_multi_character_delimiter_is_refused(String delimiter) throws Exception {
    refused(block(OK_PATH, "10", "3", OK_ENC, OK_TS, delimiter));
  }

  @Test
  void an_unknown_encoding_is_refused() throws Exception {
    refused(block(OK_PATH, "10", "3", "\"no-such-charset\"", OK_TS, OK_DELIM));
  }

  @Test
  void an_unparsable_timestamp_is_refused() throws Exception {
    refused(block(OK_PATH, "10", "3", OK_ENC, "\"yesterday\"", OK_DELIM));
  }

  private static void refused(String block) throws Exception {
    JsonNode node = cursor(block);
    assertThatThrownBy(() -> FileSourceCursorReader.InProgress.fromCursor(node))
        .isInstanceOf(ConnectorException.class)
        .hasMessageContaining("refusing to restart the partially-ingested file");
  }
}
