package io.lumnik.hub.connector.jdbc;

import io.lumnik.hub.connector.jdbc.dialect.JdbcDialectRegistry;
import io.lumnik.jdbc.postgresql.PostgresDialect;
import org.junit.jupiter.api.Test;

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
}
