package io.lumnik.hub.connector.csv;

import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * validateConfig must accept the modern nested {@code transport.path} schema (the form the apply
 * handler stores) as well as the legacy top-level {@code path}, and must not run a local
 * file-existence check for remote transports.
 */
class CsvValidateConfigTest {

  private final CsvFileConnectorType type = new CsvFileConnectorType();

  @Test
  void modern_transport_glob_path_is_valid() {
    assertThatCode(() -> type.validateConfig(Map.of(
        "transport", Map.of("kind", "local", "path", "/imports/*.csv"))))
        .doesNotThrowAnyException();
  }

  @Test
  void legacy_top_level_glob_path_is_valid() {
    assertThatCode(() -> type.validateConfig(Map.of("path", "/imports/*.csv")))
        .doesNotThrowAnyException();
  }

  @Test
  void remote_transport_path_is_not_existence_checked() {
    // s3 prefix is not a local file — must not trigger "CSV file not found".
    assertThatCode(() -> type.validateConfig(Map.of(
        "transport", Map.of("kind", "s3", "path", "my-bucket/exports/"))))
        .doesNotThrowAnyException();
  }

  @Test
  void missing_path_everywhere_fails_loudly() {
    assertThatThrownBy(() -> type.validateConfig(Map.of("parser", Map.of("has_header", true))))
        .isInstanceOf(ConfigValidationException.class);
  }

  @Test
  void local_nonglob_missing_file_still_fails() {
    assertThatThrownBy(() -> type.validateConfig(Map.of(
        "transport", Map.of("kind", "local", "path", "/no/such/file-xyz.csv"))))
        .isInstanceOf(ConfigValidationException.class)
        .hasMessageContaining("not found");
  }
}
