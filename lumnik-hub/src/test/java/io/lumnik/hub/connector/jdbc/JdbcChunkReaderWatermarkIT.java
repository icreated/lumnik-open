package io.lumnik.hub.connector.jdbc;

import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.hub.connector.jdbc.dialect.PostgresDialect;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        JdbcChunkReader.Mode.WATERMARK, new PostgresDialect(), registry);

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
    int changed = switch (r) {
      case ReadResult.HasMore<Map<String, Object>> hm -> hm.records().size();
      case ReadResult.EndOfStream<Map<String, Object>> eos -> eos.records().size();
      case ReadResult.EmptyButOpen<Map<String, Object>> ignored -> 0;
    };
    assertEquals(10, changed);
    registry.evictAll();
  }

  /**
   * Drains all rows and returns the cursor to use for the next incremental run.
   *
   * <p>When the final batch is an EndOfStream, we must build the resume cursor from the last
   * row in that batch, because HasMore never fired and the cursor variable was never updated.
   */
  private ConnectorCursor drainAll(JdbcChunkReader r, ConnectorCursor cursor) {
    ConnectorCursor last = cursor;
    while (true) {
      ReadResult<Map<String, Object>> res = r.read(500, cursor, null);
      if (res instanceof ReadResult.HasMore<Map<String, Object>> hm) {
        last = hm.nextCursor();
        cursor = last;
      } else if (res instanceof ReadResult.EndOfStream<Map<String, Object>> eos) {
        List<Map<String, Object>> recs = eos.records();
        if (!recs.isEmpty()) {
          // Build a JdbcCursor from the last row so the next run resumes after it
          Map<String, Object> lastRow = recs.get(recs.size() - 1);
          Object wmRaw = lastRow.get("updated");
          String wmStr = wmRaw != null ? wmRaw.toString() : null;
          Map<String, Object> pk = new LinkedHashMap<>();
          pk.put("id", lastRow.get("id"));
          last = new JdbcCursor("updated", wmStr, pk).toConnectorCursor();
        }
        return last;
      } else {
        return last;
      }
    }
  }
}
