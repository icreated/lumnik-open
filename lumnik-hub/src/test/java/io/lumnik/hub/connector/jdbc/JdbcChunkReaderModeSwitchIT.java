package io.lumnik.hub.connector.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.jdbc.postgresql.PostgresDialect;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A re-applied manifest that changes {@code mode}, {@code watermark_column} or {@code pk_columns}
 * meets the cursor the PREVIOUS configuration persisted — {@code lm apply} upserts the endpoint
 * and leaves {@code connector.connector_cursor} untouched. Honouring that cursor reads nothing or
 * loses rows, silently, run after run. Expected: a cursor this reader would not itself write is
 * ignored and the table restarts from the beginning ({@code _row_hash} dedup absorbs the re-read).
 */
@Testcontainers
class JdbcChunkReaderModeSwitchIT {

  @Container
  static PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("s").withUsername("u").withPassword("p");

  static final HikariPoolRegistry REGISTRY = new HikariPoolRegistry();

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

  @AfterAll
  static void tearDown() {
    REGISTRY.evictAll();
  }

  @Test
  void watermark_reader_ignores_the_pk_only_cursor_a_full_run_left_behind() {
    // The exact shape a full-mode run persists: {"pk":{"id":100},"col":"","type":"JdbcWatermark"}
    ConnectorCursor fullModeCursor = new JdbcCursor("", null, Map.of("id", 100L)).toConnectorCursor();

    JdbcChunkReader watermark = reader("updated", JdbcChunkReader.Mode.WATERMARK);
    assertEquals(100, count(watermark.read(500, fullModeCursor, null)),
        "a full-mode cursor carries no watermark value; honouring it binds `updated > NULL`");
  }

  @Test
  void full_reader_ignores_the_composite_cursor_a_watermark_run_left_behind() {
    ConnectorCursor watermarkCursor =
        new JdbcCursor("updated", "2026-01-01 00:00:50.0", Map.of("id", 50L)).toConnectorCursor();

    JdbcChunkReader full = reader(null, JdbcChunkReader.Mode.FULL);
    assertEquals(100, count(full.read(500, watermarkCursor, null)),
        "a watermark cursor's pk is the latest-updated row's, not a full-scan position");
  }

  @Test
  void full_reader_ignores_a_cursor_keyed_on_a_pk_column_it_no_longer_declares() {
    // pk_columns changed from [code] to [id]: the cursor has no `id` key, so `id > ?` binds NULL.
    ConnectorCursor otherPkCursor = new JdbcCursor("", null, Map.of("code", "c50")).toConnectorCursor();

    JdbcChunkReader full = reader(null, JdbcChunkReader.Mode.FULL);
    assertEquals(100, count(full.read(500, otherPkCursor, null)),
        "a cursor keyed on another pk column is not a position on this one");
  }

  /**
   * The shape a build written before the key order was recorded persisted: a {@code pk} object and
   * no {@code keys} array. It is single-column, so its order is not in question — it is this
   * reader's own position and must be RESUMED, not restarted. Getting this wrong would restart
   * every jdbc endpoint in every deployment on the first run after the upgrade.
   */
  @Test
  void a_cursor_written_before_the_key_order_was_recorded_still_resumes() throws Exception {
    ObjectNode legacy = new ObjectMapper().createObjectNode();
    legacy.put("type", "JdbcWatermark");
    legacy.put("col", "");
    legacy.putObject("pk").put("id", 50L);

    JdbcChunkReader full = reader(null, JdbcChunkReader.Mode.FULL);
    assertEquals(50, count(full.read(500, new ConnectorCursor(legacy, Instant.now()), null)),
        "rows 51..100 — a cursor with no keys array is honoured, not declared foreign");
  }

  private static JdbcChunkReader reader(String watermarkColumn, JdbcChunkReader.Mode mode) {
    return new JdbcChunkReader(PG.getJdbcUrl(), "u", "p", "postgres",
        "public", "t", watermarkColumn, List.of("id"), mode, new PostgresDialect(), REGISTRY);
  }

  private static int count(ReadResult<Map<String, Object>> r) {
    return switch (r) {
      case ReadResult.HasMore<Map<String, Object>> hm -> hm.records().size();
      case ReadResult.EndOfStream<Map<String, Object>> eos -> eos.records().size();
      case ReadResult.EmptyButOpen<Map<String, Object>> ignored -> 0;
    };
  }
}
