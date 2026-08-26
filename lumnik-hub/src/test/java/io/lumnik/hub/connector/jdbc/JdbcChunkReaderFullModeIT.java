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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies FULL mode: re-reads all rows each run when started from an empty cursor.
 *
 * <p>Full mode does not track a watermark; callers start each run from ConnectorCursor.empty().
 * Within a single run, pagination uses PK-only tiebreak to avoid missing rows mid-scan.
 */
@Testcontainers
class JdbcChunkReaderFullModeIT {

  @Container
  static PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("s").withUsername("u").withPassword("p");

  @BeforeAll
  static void seed() throws Exception {
    try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "u", "p");
         var st = c.createStatement()) {
      st.execute("CREATE TABLE ref (id INT PRIMARY KEY, label TEXT NOT NULL)");
      for (int i = 1; i <= 100; i++) {
        st.execute("INSERT INTO ref VALUES (" + i + ", 'l" + i + "')");
      }
    }
  }

  @Test
  void full_mode_re_reads_all_rows_each_run() {
    HikariPoolRegistry registry = new HikariPoolRegistry();
    JdbcChunkReader r = new JdbcChunkReader(PG.getJdbcUrl(), "u", "p", "postgres",
        "public", "ref", null, List.of("id"),
        JdbcChunkReader.Mode.FULL, new PostgresDialect(), registry);

    int firstRun = drainCount(r, ConnectorCursor.empty());
    int secondRun = drainCount(r, ConnectorCursor.empty());

    assertEquals(100, firstRun, "First run should read all 100 rows");
    assertEquals(100, secondRun, "Second run from empty cursor should also read all 100 rows");
    registry.evictAll();
  }

  private int drainCount(JdbcChunkReader r, ConnectorCursor cursor) {
    int total = 0;
    int safetyLimit = 0;
    while (true) {
      ReadResult<Map<String, Object>> res = r.read(40, cursor, null);
      switch (res) {
        case ReadResult.HasMore<Map<String, Object>> hm -> {
          total += hm.records().size();
          cursor = hm.nextCursor();
        }
        case ReadResult.EndOfStream<Map<String, Object>> eos -> {
          total += eos.records().size();
          return total;
        }
        case ReadResult.EmptyButOpen<Map<String, Object>> ignored -> {
          return total;
        }
      }
      if (++safetyLimit > 20) fail("Too many chunks in full-mode drain");
    }
  }
}
