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

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class JdbcChunkReaderIT {

  @Container
  static PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("source").withUsername("u").withPassword("p");

  @BeforeAll
  static void seed() throws Exception {
    try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "u", "p");
         var st = c.createStatement()) {
      st.execute("CREATE TABLE c_bp (id BIGINT PRIMARY KEY, name TEXT, updated TIMESTAMP NOT NULL)");
      for (int i = 1; i <= 1000; i++) {
        st.execute("INSERT INTO c_bp VALUES (" + i + ", 'name-" + i +
            "', TIMESTAMP '2026-01-01 00:00:00' + (interval '1 second' * " + i + "))");
      }
    }
  }

  @Test
  void reads_in_chunks_until_end_of_stream() {
    HikariPoolRegistry registry = new HikariPoolRegistry();
    JdbcChunkReader reader = new JdbcChunkReader(
        PG.getJdbcUrl(), "u", "p", "postgres", "public", "c_bp", "updated",
        List.of("id"), JdbcChunkReader.Mode.WATERMARK,
        new PostgresDialect(), registry);

    ConnectorCursor cursor = ConnectorCursor.empty();
    int chunks = 0, total = 0;
    while (true) {
      ReadResult<Map<String, Object>> r = reader.read(250, cursor, null);
      chunks++;
      if (r instanceof ReadResult.HasMore<Map<String, Object>> hm) {
        assertEquals(250, hm.records().size());
        total += hm.records().size();
        cursor = hm.nextCursor();
      } else if (r instanceof ReadResult.EndOfStream<Map<String, Object>> eos) {
        total += eos.records().size();
        break;
      }
      if (chunks > 10) fail("Too many chunks — runaway loop");
    }
    assertEquals(1000, total);
    registry.evictAll();
  }
}
