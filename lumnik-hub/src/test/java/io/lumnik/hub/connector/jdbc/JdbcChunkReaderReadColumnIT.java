package io.lumnik.hub.connector.jdbc;

import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.jdbc.postgresql.PostgresDialect;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Map;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The reader asks the DIALECT for every cell: a provider decides what a column's value is. */
@Testcontainers
class JdbcChunkReaderReadColumnIT {

  @Container
  static PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("source").withUsername("u").withPassword("p");

  @BeforeAll
  static void seed() throws Exception {
    try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "u", "p");
         var st = c.createStatement()) {
      st.execute("CREATE TABLE t (id BIGINT PRIMARY KEY, name TEXT)");
      st.execute("INSERT INTO t VALUES (1, 'a'), (2, 'b')");
    }
  }

  private static ReadResult<Map<String, Object>> readAll(PostgresDialect dialect) {
    HikariPoolRegistry registry = new HikariPoolRegistry();
    try {
      JdbcChunkReader reader = new JdbcChunkReader(PG.getJdbcUrl(), "u", "p", "postgres", "public", "t", null,
          List.of("id"), JdbcChunkReader.Mode.FULL, dialect, registry, ZoneOffset.UTC);
      return reader.read(10, ConnectorCursor.empty(), null);
    } finally {
      registry.evictAll();
    }
  }

  @Test
  void every_cell_goes_through_the_dialects_readColumn() {
    ReadResult<Map<String, Object>> r = readAll(new PostgresDialect() {
      @Override
      public Object readColumn(ResultSet rs, int index) throws SQLException {
        return "x:" + rs.getObject(index);
      }
    });
    ReadResult.HasMore<Map<String, Object>> rows = (ReadResult.HasMore<Map<String, Object>>) r;
    assertEquals(2, rows.records().size());
    assertEquals(Map.of("id", "x:1", "name", "x:a"), rows.records().get(0));
  }

  @Test
  void the_default_hook_is_plain_getObject_so_postgresql_values_are_unchanged() {
    ReadResult.HasMore<Map<String, Object>> rows = (ReadResult.HasMore<Map<String, Object>>) readAll(new PostgresDialect());
    assertEquals(Map.of("id", 1L, "name", "a"), rows.records().get(0));
  }

  @Test
  void a_refusal_from_the_hook_fails_the_read_naming_the_table() {
    RuntimeException e = assertThrows(RuntimeException.class,
        () -> readAll(new PostgresDialect() {
          @Override
          public Object readColumn(ResultSet rs, int index) throws SQLException {
            throw new SQLFeatureNotSupportedException("column NAME has unsupported type X");
          }
        }));
    assertTrue(e.getMessage().contains("public.t") && e.getMessage().contains("column NAME"), e.getMessage());
  }
}
