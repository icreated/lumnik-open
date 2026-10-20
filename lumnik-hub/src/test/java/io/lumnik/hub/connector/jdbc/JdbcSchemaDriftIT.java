package io.lumnik.hub.connector.jdbc;

import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.jdbc.postgresql.PostgresDialect;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that JdbcChunkReader dynamically adapts to schema changes on the
 * source (i.e., a new column added via ALTER TABLE is picked up on the next
 * read without reconfiguring the reader).
 *
 * <p>This relies on JdbcChunkReader using {@code SELECT *}, which means the
 * column list is re-evaluated at query time — not cached from the first read.
 */
@Testcontainers
class JdbcSchemaDriftIT {

  @Container
  static PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("s").withUsername("u").withPassword("p");

  @BeforeAll
  static void seed() throws Exception {
    try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "u", "p");
         var st = c.createStatement()) {
      st.execute("CREATE TABLE t (id BIGINT PRIMARY KEY, name TEXT, updated TIMESTAMP NOT NULL)");
      st.execute("INSERT INTO t VALUES (1, 'a', '2026-01-01 00:00:01')");
    }
  }

  @Test
  void new_column_added_on_source_is_picked_up_by_reader() throws Exception {
    HikariPoolRegistry registry = new HikariPoolRegistry();
    JdbcChunkReader r = new JdbcChunkReader(
        PG.getJdbcUrl(), "u", "p", "postgres",
        "public", "t", "updated", List.of("id"),
        JdbcChunkReader.Mode.WATERMARK, new PostgresDialect(), registry, ZoneOffset.UTC);

    // ── First read: 'note' column does not exist yet ──────────────────────────
    ReadResult<Map<String, Object>> r1 = r.read(10, ConnectorCursor.empty(), null);
    Map<String, Object> first = ((ReadResult.HasMore<Map<String, Object>>) r1).records().get(0);
    assertFalse(first.containsKey("note"), "Column 'note' must not exist before ALTER TABLE");

    // ── Add a column and update the row ──────────────────────────────────────
    try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "u", "p");
         var st = c.createStatement()) {
      st.execute("ALTER TABLE t ADD COLUMN note TEXT");
      st.execute("UPDATE t SET note = 'hello', updated = '2026-02-01 00:00:01' WHERE id = 1");
    }

    // ── Second read from empty cursor: reader must see the new column ─────────
    // Using ConnectorCursor.empty() so the watermark filter starts from epoch,
    // guaranteeing the updated row is returned regardless of prior cursor state.
    ReadResult<Map<String, Object>> r2 = r.read(10, ConnectorCursor.empty(), null);
    Map<String, Object> updated = ((ReadResult.HasMore<Map<String, Object>>) r2).records().get(0);
    assertTrue(updated.containsKey("note"), "Column 'note' must appear after ALTER TABLE");
    assertEquals("hello", updated.get("note"));

    registry.evictAll();
  }
}
