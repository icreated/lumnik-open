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

@Testcontainers
class JdbcChunkReaderWatermarkIT {

  @Container
  static PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("s").withUsername("u").withPassword("p");

  @BeforeAll
  static void seed() throws Exception {
    try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "u", "p");
         var st = c.createStatement()) {
      st.execute("CREATE TABLE t (id BIGINT PRIMARY KEY, name TEXT, updated TIMESTAMP NOT NULL)");
      st.execute("CREATE TABLE tn (id BIGINT PRIMARY KEY, updated TIMESTAMP)");
      st.execute("CREATE TABLE tall (id BIGINT PRIMARY KEY, updated TIMESTAMP)");
      st.execute("INSERT INTO tall VALUES (1, NULL), (2, NULL)");
      for (int i = 1; i <= 100; i++) {
        st.execute("INSERT INTO t VALUES (" + i + ", 'n" + i +
            "', TIMESTAMP '2026-01-01 00:00:00' + (interval '1 second' * " + i + "))");
      }
    }
  }

  @Test
  void second_run_after_update_reads_only_changed_rows() throws Exception {
    HikariPoolRegistry registry = new HikariPoolRegistry();
    JdbcChunkReader reader = new JdbcChunkReader(PG.getJdbcUrl(), "u", "p", "postgres",
        "public", "t", "updated", List.of("id"),
        JdbcChunkReader.Mode.WATERMARK, new PostgresDialect(), registry, ZoneOffset.UTC);

    // Drain all 100 initial rows; capture final cursor position
    ConnectorCursor cursor = drainAll(reader, ConnectorCursor.empty());

    // Update 10 rows to a later timestamp
    try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "u", "p");
         var st = c.createStatement()) {
      st.execute("UPDATE t SET updated = TIMESTAMP '2026-02-01 00:00:00' " +
                 "+ (interval '1 second' * id) WHERE id <= 10");
    }

    // Resume from cursor — should see only the 10 updated rows
    ReadResult<Map<String, Object>> r = reader.read(500, cursor, null);
    int changed = ((ReadResult.HasMore<Map<String, Object>>) r).records().size();
    assertEquals(10, changed);
    registry.evictAll();
  }

  @Test
  void a_first_read_that_ends_on_a_row_without_watermark_leaves_a_cursor_that_still_resumes() throws Exception {
    // #1921: the cursor was built from the chunk's LAST row, a NULL-watermark one (NULLs sort last), so
    // it held no value and `(wm, id) > (NULL, …)` read nothing on every later run.
    try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "u", "p");
         var st = c.createStatement()) {
      st.execute("INSERT INTO tn VALUES (1, TIMESTAMP '2026-01-01 00:00:01'), "
          + "(2, TIMESTAMP '2026-01-01 00:00:02'), (3, NULL)");
    }
    HikariPoolRegistry registry = new HikariPoolRegistry();
    JdbcChunkReader reader = new JdbcChunkReader(PG.getJdbcUrl(), "u", "p", "postgres",
        "public", "tn", "updated", List.of("id"),
        JdbcChunkReader.Mode.WATERMARK, new PostgresDialect(), registry, ZoneOffset.UTC);

    ReadResult<Map<String, Object>> first = reader.read(500, ConnectorCursor.empty(), null);
    assertEquals(3, ((ReadResult.HasMore<Map<String, Object>>) first).records().size(),
        "the first pass still reads the row with no watermark");
    ConnectorCursor cursor = ((ReadResult.HasMore<Map<String, Object>>) first).nextCursor();

    try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "u", "p");
         var st = c.createStatement()) {
      st.execute("UPDATE tn SET updated = TIMESTAMP '2026-02-01 00:00:00' WHERE id = 1");
    }

    ReadResult<Map<String, Object>> next = reader.read(500, cursor, null);
    assertEquals(1, ((ReadResult.HasMore<Map<String, Object>>) next).records().size(),
        "the cursor rests on the last row that HAS a watermark, so the changed row arrives");
    registry.evictAll();
  }

  @Test
  void a_chunk_of_rows_all_without_watermark_is_read_once_and_ends_the_stream() throws Exception {
    // No row can be a resume point: handing back the same (empty) cursor as HasMore would read the
    // same rows forever.
    HikariPoolRegistry registry = new HikariPoolRegistry();
    JdbcChunkReader reader = new JdbcChunkReader(PG.getJdbcUrl(), "u", "p", "postgres",
        "public", "tall", "updated", List.of("id"),
        JdbcChunkReader.Mode.WATERMARK, new PostgresDialect(), registry, ZoneOffset.UTC);

    ReadResult<Map<String, Object>> r = reader.read(500, ConnectorCursor.empty(), null);
    assertInstanceOf(ReadResult.EndOfStream.class, r);
    assertEquals(2, ((ReadResult.EndOfStream<Map<String, Object>>) r).records().size());
    registry.evictAll();
  }

  /** Drains all rows; the cursor of the last HasMore is where the next incremental run resumes. */
  private ConnectorCursor drainAll(JdbcChunkReader r, ConnectorCursor cursor) {
    while (r.read(500, cursor, null) instanceof ReadResult.HasMore<Map<String, Object>> hm) {
      cursor = hm.nextCursor();
    }
    return cursor;
  }
}
