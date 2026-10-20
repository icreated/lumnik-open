package io.lumnik.hub.connector.jdbc;

import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.jdbc.postgresql.PostgresDialect;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The engine persists a cursor only on {@code HasMore}. A reader ending on a LOADED
 * {@code EndOfStream} leaves the persisted cursor at the last full chunk: the next run re-reads
 * the tail, and a table smaller than one chunk never persists a cursor at all — watermark mode
 * silently becomes a full re-read. So: every non-empty chunk is HasMore, in both modes, whatever
 * the chunk size, and the stream ends on a read that finds nothing.
 */
@Testcontainers
class JdbcChunkReaderTailCursorIT {

  @Container
  static PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("s").withUsername("u").withPassword("p");

  static final HikariPoolRegistry REGISTRY = new HikariPoolRegistry();
  static final int ROWS = 100;

  @BeforeAll
  static void seed() throws Exception {
    try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "u", "p");
         var st = c.createStatement()) {
      st.execute("CREATE TABLE t (id BIGINT PRIMARY KEY, name TEXT, updated TIMESTAMP NOT NULL)");
      for (int i = 1; i <= ROWS; i++) {
        st.execute("INSERT INTO t VALUES (" + i + ", 'n" + i +
            "', TIMESTAMP '2026-01-01 00:00:00' + (interval '1 second' * " + i + "))");
      }
    }
  }

  @AfterAll
  static void tearDown() {
    REGISTRY.evictAll();
  }

  // chunk 500: the whole table is one partial chunk; chunk 30: a 10-row tail after three full ones.
  @ParameterizedTest(name = "{0}, chunk {1}")
  @CsvSource({"WATERMARK, 500", "WATERMARK, 30", "FULL, 500", "FULL, 30"})
  void the_readers_own_cursors_reach_the_last_row(JdbcChunkReader.Mode mode, int chunk) {
    JdbcChunkReader reader = new JdbcChunkReader(PG.getJdbcUrl(), "u", "p", "postgres",
        "public", "t", mode == JdbcChunkReader.Mode.WATERMARK ? "updated" : null, List.of("id"),
        mode, new PostgresDialect(), REGISTRY, ZoneOffset.UTC);

    ConnectorCursor cursor = ConnectorCursor.empty();
    int rows = 0;
    ReadResult<Map<String, Object>> r;
    while ((r = reader.read(chunk, cursor, null)) instanceof ReadResult.HasMore<Map<String, Object>> hm) {
      rows += hm.records().size();
      cursor = hm.nextCursor();
    }
    assertEquals(ROWS, rows, "the HasMore chunks alone carry the whole table");
    assertEquals(List.of(), ((ReadResult.EndOfStream<?>) r).records(),
        "the stream ends on a read that finds nothing");
  }
}
