package io.lumnik.hub.connector.csv;

import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.hub.connector.transport.LocalRoot;
import io.lumnik.hub.connector.transport.LocalRoots;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

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

  /** #2001: no tenant here, so no local file is looked at — not the whole root, not another tenant's folder. */
  @Test
  void a_local_path_is_not_looked_at_without_a_tenant() {
    type.localRoots = new LocalRoots(LocalRoot.of(Optional.empty()));
    assertThatCode(() -> type.validateConfig(Map.of(
        "transport", Map.of("kind", "local", "path", "no/such/file-xyz.csv"))))
        .doesNotThrowAnyException();
  }
}
