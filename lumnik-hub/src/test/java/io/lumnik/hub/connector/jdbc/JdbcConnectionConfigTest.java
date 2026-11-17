package io.lumnik.hub.connector.jdbc;

import io.lumnik.hub.connector.jdbc.dialect.JdbcDialectRegistry;
import io.lumnik.jdbc.postgresql.PostgresDialect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JdbcConnectionConfigTest {

  private static final JdbcDialectRegistry REGISTRY = JdbcDialectRegistry.of(List.of(new PostgresDialect()));

  @Test
  void parses_postgres_url_and_derives_driver_and_dialect() {
    JdbcConnectionConfig c = JdbcConnectionConfig.parse(
        "jdbc:postgresql://host:5432/db", "user", "pwd", REGISTRY);
    assertEquals("jdbc:postgresql://host:5432/db", c.url());
    assertEquals("user", c.user());
    assertEquals("pwd", c.password());
    assertEquals("postgres", c.dialectId());
  }

  @Test
  void rejects_url_without_jdbc_prefix() {
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> JdbcConnectionConfig.parse("postgresql://host/db", "u", "p", REGISTRY));
    assertTrue(ex.getMessage().contains("jdbc:"));
  }

  @Test
  void rejects_a_url_no_installed_dialect_accepts() {
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> JdbcConnectionConfig.parse("jdbc:mariadb://host/db", "u", "p", REGISTRY));
    assertTrue(ex.getMessage().contains("mariadb"));
    assertTrue(ex.getMessage().contains("postgres"));
  }

  @Test
  void rejects_blank_user() {
    assertThrows(IllegalArgumentException.class,
        () -> JdbcConnectionConfig.parse("jdbc:postgresql://h/d", "  ", "p", REGISTRY));
  }

  @Test
  void to_string_masks_password() {
    JdbcConnectionConfig c = JdbcConnectionConfig.parse(
        "jdbc:postgresql://host/db", "user", "supersecret", REGISTRY);
    String s = c.toString();
    assertFalse(s.contains("supersecret"));
    assertTrue(s.contains("***"));
  }

  /** #2002: a URL that does not parse is refused by its shape, never quoted: it can carry the password. */
  @Test
  void a_malformed_url_is_refused_without_being_quoted() {
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> JdbcConnectionConfig.parse("jdbc:Postgres://user:s3cret@host/db", "u", "p", REGISTRY));
    assertFalse(ex.getMessage().contains("s3cret"), ex.getMessage());
    assertTrue(ex.getMessage().contains("jdbc:<scheme>:"), ex.getMessage());
  }

  @Test
  void to_string_drops_a_password_the_url_carries() {
    JdbcConnectionConfig c = JdbcConnectionConfig.parse(
        "jdbc:postgresql://host/db?user=u&password=s3cret", "user", "p", REGISTRY);
    assertFalse(c.toString().contains("s3cret"), c.toString());
    assertTrue(c.toString().contains("url=jdbc:postgresql:…"), c.toString());
  }

  /** #2002: what a log line may show of a JDBC URL — its scheme, never a piece a password could hide in. */
  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "jdbc:postgresql://user:s3cret@host:5432/db | jdbc:postgresql:…",
      "jdbc:postgresql://user:s3;cr?et@host/db | jdbc:postgresql:…",
      "jdbc:postgresql://host/db?user=u&password=s3cret@x | jdbc:postgresql:…",
      "jdbc:sqlserver://host:1433;user=u;password=s3cret | jdbc:sqlserver:…",
      "jdbc:oracle:thin:scott/s3cret@//host:1521/svc | jdbc:oracle:…",
      "jdbc | jdbc:…",
  })
  void for_display_shows_the_scheme_alone(String url, String shown) {
    assertEquals(shown, JdbcConnectionConfig.forDisplay(url));
  }
}
