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

  /** Drains all rows; the cursor of the last HasMore is where the next incremental run resumes. */
  private ConnectorCursor drainAll(JdbcChunkReader r, ConnectorCursor cursor) {
    while (r.read(500, cursor, null) instanceof ReadResult.HasMore<Map<String, Object>> hm) {
      cursor = hm.nextCursor();
    }
    return cursor;
  }
}
