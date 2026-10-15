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
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that pagination is stable when all rows share the same watermark timestamp.
 *
 * <p>Cursor carries (col=updated, value="2026-05-23 10:00:00.0", pk={id:25}). On resume,
 * the query becomes: WHERE updated > '...' OR (updated = '...' AND id > 25). The PostgreSQL
 * JDBC driver accepts a String value for TIMESTAMP columns via setObject, so no explicit cast
 * is needed in JdbcChunkReader.bindCursorParams.
 */
@Testcontainers
class JdbcChunkReaderTiebreakIT {

  @Container
  static PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("s").withUsername("u").withPassword("p");

  @BeforeAll
  static void seed() throws Exception {
    try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "u", "p");
         var st = c.createStatement()) {
      st.execute("CREATE TABLE t (id BIGINT PRIMARY KEY, updated TIMESTAMP NOT NULL)");
      for (int i = 1; i <= 50; i++) {
        st.execute("INSERT INTO t VALUES (" + i + ", TIMESTAMP '2026-05-23 10:00:00')");
      }
    }
  }

  @Test
  void pagination_is_stable_when_watermark_is_constant() {
    HikariPoolRegistry registry = new HikariPoolRegistry();
    JdbcChunkReader reader = new JdbcChunkReader(PG.getJdbcUrl(), "u", "p", "postgres",
        "public", "t", "updated", List.of("id"),
        JdbcChunkReader.Mode.WATERMARK, new PostgresDialect(), registry);

    ConnectorCursor cursor = ConnectorCursor.empty();
    Set<Long> seen = new HashSet<>();
    int safetyLimit = 0;

    while (true) {
      ReadResult<Map<String, Object>> r = reader.read(25, cursor, null);

      List<Map<String, Object>> recs = switch (r) {
        case ReadResult.HasMore<Map<String, Object>> hm -> hm.records();
        case ReadResult.EndOfStream<Map<String, Object>> eos -> eos.records();
        case ReadResult.EmptyButOpen<Map<String, Object>> ignored -> List.of();
      };

      for (Map<String, Object> row : recs) {
        long id = ((Number) row.get("id")).longValue();
        assertTrue(seen.add(id), "duplicate id seen: " + id);
      }

      if (r instanceof ReadResult.HasMore<Map<String, Object>> hm) {
        cursor = hm.nextCursor();
      } else {
        break;
      }

      if (++safetyLimit > 10) fail("Too many chunks — tiebreak pagination is looping");
    }

    assertEquals(50, seen.size(), "Expected 50 distinct rows, got " + seen.size());
    registry.evictAll();
  }
}
