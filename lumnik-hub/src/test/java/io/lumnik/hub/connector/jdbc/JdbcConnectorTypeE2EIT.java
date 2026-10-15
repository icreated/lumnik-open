package io.lumnik.hub.connector.jdbc;

import io.lumnik.hub.connector.api.types.TriggerMode;
import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.hub.connector.jdbc.dialect.JdbcDialectRegistry;
import io.lumnik.jdbc.postgresql.PostgresDialect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain JUnit 5 — no @QuarkusTest.
 * Wires JdbcConnectorType manually (same approach as SchemaIntrospectorIT).
 * Only metadata and validateConfig are exercised — no real DB needed.
 */
class JdbcConnectorTypeE2EIT {

  private JdbcConnectorType type;

  @BeforeEach
  void setup() throws Exception {
    JdbcDialectRegistry dialectRegistry = JdbcDialectRegistry.of(List.of(new PostgresDialect()));

    HikariPoolRegistry poolRegistry = new HikariPoolRegistry();

    type = new JdbcConnectorType();
    Field dr = JdbcConnectorType.class.getDeclaredField("dialectRegistry");
    dr.setAccessible(true);
    dr.set(type, dialectRegistry);

    Field pr = JdbcConnectorType.class.getDeclaredField("poolRegistry");
    pr.setAccessible(true);
    pr.set(type, poolRegistry);
  }

  @Test
  void type_id_is_jdbc_generic() {
    assertEquals("jdbc-generic", type.id());
  }

  @Test
  void supported_trigger_modes_contains_manual_and_poll() {
    var modes = type.supportedTriggerModes();
    assertTrue(modes.contains(TriggerMode.MANUAL));
    assertTrue(modes.contains(TriggerMode.POLL));
  }

  @Test
  void valid_config_passes_validation() {
    Map<String, Object> cfg = Map.of(
        "url",              "jdbc:postgresql://localhost:5432/db",
        "user",             "u",
        "dialect",          "postgres",
        "schema",           "public",
        "name",             "orders",
        "mode",             "watermark",
        "watermark_column", "updated_at",
        "pk_columns",       List.of("id"));
    assertDoesNotThrow(() -> type.validateConfig(cfg));
  }

  @Test
  void invalid_config_missing_url_fails_validation() {
    Map<String, Object> cfg = Map.of("user", "u");
    assertThrows(ConfigValidationException.class, () -> type.validateConfig(cfg));
  }
}
