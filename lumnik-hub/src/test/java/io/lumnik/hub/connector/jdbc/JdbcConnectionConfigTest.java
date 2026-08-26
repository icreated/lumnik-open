package io.lumnik.hub.connector.jdbc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JdbcConnectionConfigTest {

  @Test
  void parses_postgres_url_and_derives_driver_and_dialect() {
    JdbcConnectionConfig c = JdbcConnectionConfig.parse(
        "jdbc:postgresql://host:5432/db", "user", "pwd");
    assertEquals("jdbc:postgresql://host:5432/db", c.url());
    assertEquals("user", c.user());
    assertEquals("pwd", c.password());
    assertEquals("org.postgresql.Driver", c.driverClass());
    assertEquals("postgres", c.dialectId());
  }

  @Test
  void rejects_url_without_jdbc_prefix() {
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> JdbcConnectionConfig.parse("postgresql://host/db", "u", "p"));
    assertTrue(ex.getMessage().contains("jdbc:"));
  }

  @Test
  void rejects_unsupported_dialect_in_v1() {
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> JdbcConnectionConfig.parse("jdbc:mariadb://host/db", "u", "p"));
    assertTrue(ex.getMessage().contains("mariadb"));
    assertTrue(ex.getMessage().contains("postgres"));
  }

  @Test
  void rejects_blank_user() {
    assertThrows(IllegalArgumentException.class,
        () -> JdbcConnectionConfig.parse("jdbc:postgresql://h/d", "  ", "p"));
  }

  @Test
  void to_string_masks_password() {
    JdbcConnectionConfig c = JdbcConnectionConfig.parse(
        "jdbc:postgresql://host/db", "user", "supersecret");
    String s = c.toString();
    assertFalse(s.contains("supersecret"));
    assertTrue(s.contains("***"));
  }
}
