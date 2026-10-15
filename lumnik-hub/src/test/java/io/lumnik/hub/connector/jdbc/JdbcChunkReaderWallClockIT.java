package io.lumnik.hub.connector.jdbc;

import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.hub.connector.mapping.writer.WallClockTimestamp;
import io.lumnik.jdbc.postgresql.PostgresDialect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1093 on a real PostgreSQL source, the hub JVM in New York: a `timestamp` column is marked as a
 * wall-clock time, a `timestamptz` one is not, and a watermark on the marked column still pages
 * through every row exactly once — the cursor is untouched by the mark.
 */
@Testcontainers
class JdbcChunkReaderWallClockIT {

  @Container
  static PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("source").withUsername("u").withPassword("p");

  private TimeZone saved;

  @BeforeAll
  static void seed() throws Exception {
    try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "u", "p");
         var st = c.createStatement()) {
      st.execute("CREATE TABLE t (id BIGINT PRIMARY KEY, ts TIMESTAMP, tz TIMESTAMPTZ)");
      st.execute("INSERT INTO t VALUES (1, '2026-08-02 23:30:00', '2026-08-02 23:30:00+00'),"
          + " (2, '2026-08-02 23:45:00', '2026-08-02 23:45:00+00'), (3, '2026-08-03 00:15:00', '2026-08-03 00:15:00+00')");
    }
  }

  @BeforeEach
  void inNewYork() {
    saved = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
  }

  @AfterEach
  void restore() {
    TimeZone.setDefault(saved);
  }

  private static JdbcChunkReader reader(JdbcChunkReader.Mode mode, String watermark, HikariPoolRegistry registry) {
    return new JdbcChunkReader(PG.getJdbcUrl(), "u", "p", "postgres", "public", "t", watermark,
        List.of("id"), mode, new PostgresDialect(), registry);
  }

  @Test
  void a_timestamp_column_is_marked_a_timestamptz_column_is_not() {
    HikariPoolRegistry registry = new HikariPoolRegistry();
    try {
      ReadResult<Map<String, Object>> r = reader(JdbcChunkReader.Mode.FULL, null, registry)
          .read(10, ConnectorCursor.empty(), null);
      Map<String, Object> first = ((ReadResult.HasMore<Map<String, Object>>) r).records().get(0);
      assertThat(first.get("ts")).isInstanceOf(WallClockTimestamp.class);
      assertThat(((Timestamp) first.get("ts")).toLocalDateTime()).isEqualTo(LocalDateTime.parse("2026-08-02T23:30:00"));
      assertThat(first.get("tz")).isExactlyInstanceOf(Timestamp.class);
    } finally {
      registry.evictAll();
    }
  }

  @Test
  void a_watermark_on_a_marked_column_reads_every_row_once() {
    HikariPoolRegistry registry = new HikariPoolRegistry();
    try {
      JdbcChunkReader reader = reader(JdbcChunkReader.Mode.WATERMARK, "ts", registry);
      List<Object> ids = new ArrayList<>();
      ConnectorCursor cursor = ConnectorCursor.empty();
      for (int guard = 0; guard < 10; guard++) {
        ReadResult<Map<String, Object>> r = reader.read(1, cursor, null);
        if (!(r instanceof ReadResult.HasMore<Map<String, Object>> more)) break;
        more.records().forEach(row -> ids.add(row.get("id")));
        cursor = more.nextCursor();
      }
      assertThat(ids).containsExactly(1L, 2L, 3L);
    } finally {
      registry.evictAll();
    }
  }
}
