package io.lumnik.hub.connector.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A DATE / TIMESTAMP / TIME column inside the primary key must page like any other: the cursor goes through
 * JSON between chunks (the engine stores it in a jsonb column), and a key value that comes back as a number
 * cannot be compared to a temporal column — the read failed from the SECOND chunk on, after the first had
 * been written. The cursor is persisted as TEXT here, as the engine does, not handed over in memory.
 */
@Testcontainers
class JdbcChunkReaderTemporalKeyIT {

  @Container
  static PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("s").withUsername("u").withPassword("p");

  static final HikariPoolRegistry REGISTRY = new HikariPoolRegistry();
  static final ObjectMapper M = new ObjectMapper();
  static final int ITEMS = 3;
  static final int VERSIONS = 10;

  @BeforeAll
  static void seed() throws Exception {
    try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "u", "p"); var st = c.createStatement()) {
      // (ITEM_ID, VALID_FROM) — the shape of an ERP price or validity table.
      st.execute("CREATE TABLE prices (item_id BIGINT, valid_from TIMESTAMP, price NUMERIC, updated TIMESTAMP NOT NULL,"
          + " PRIMARY KEY (item_id, valid_from))");
      st.execute("INSERT INTO prices SELECT i, TIMESTAMP '2026-01-01 00:00:00.123456' + (interval '1 day' * v), v * 1.5,"
          + " TIMESTAMP '2026-02-01 00:00:00' FROM generate_series(1, " + ITEMS + ") i, generate_series(1, " + VERSIONS + ") v");
      st.execute("CREATE TABLE days (day DATE PRIMARY KEY, label TEXT)");
      st.execute("INSERT INTO days SELECT DATE '2026-01-01' + g, 'd' || g FROM generate_series(1, 30) g");
      st.execute("CREATE TABLE ids (id UUID PRIMARY KEY, label TEXT)");
      // Deterministic, and needing no extension on any PostgreSQL version: a failure reproduces run after run.
      st.execute("INSERT INTO ids SELECT md5('uuid-' || g)::uuid, 'u' || g FROM generate_series(1, 30) g");
      st.execute("CREATE TABLE slots (at TIME PRIMARY KEY, label TEXT)");
      st.execute("INSERT INTO slots SELECT TIME '00:00:00' + (interval '1 minute' * g), 's' || g FROM generate_series(1, 30) g");
    }
  }

  @AfterAll
  static void tearDown() {
    REGISTRY.evictAll();
  }

  @ParameterizedTest(name = "{0}, chunk {1}")
  @CsvSource({"WATERMARK, 7", "WATERMARK, 4", "FULL, 7", "FULL, 4"})
  void a_composite_key_with_a_timestamp_is_read_exactly_once(JdbcChunkReader.Mode mode, int chunk) throws Exception {
    JdbcChunkReader reader = reader("prices", mode == JdbcChunkReader.Mode.WATERMARK ? "updated" : null, mode,
        "item_id", "valid_from");
    List<Map<String, Object>> rows = drain(reader, chunk);
    Set<String> keys = new LinkedHashSet<>();
    rows.forEach(r -> keys.add(r.get("item_id") + "@" + r.get("valid_from")));
    assertEquals(ITEMS * VERSIONS, rows.size());
    assertEquals(ITEMS * VERSIONS, keys.size(), "none repeated");
  }

  @ParameterizedTest(name = "{0} key, chunk {1}")
  @CsvSource({"days, day, 7", "slots, at, 7", "ids, id, 7"})
  void a_single_date_or_time_key_is_read_exactly_once(String table, String key, int chunk) throws Exception {
    List<Map<String, Object>> rows = drain(reader(table, null, JdbcChunkReader.Mode.FULL, key), chunk);
    Set<Object> keys = new LinkedHashSet<>();
    rows.forEach(r -> keys.add(r.get(key)));
    assertEquals(30, rows.size());
    assertEquals(30, keys.size(), "none repeated");
  }

  private static JdbcChunkReader reader(String table, String watermark, JdbcChunkReader.Mode mode, String... pk) {
    return new JdbcChunkReader(PG.getJdbcUrl(), "u", "p", "postgres", "public", table, watermark, List.of(pk), mode,
        new PostgresDialect(), REGISTRY, ZoneOffset.UTC);
  }

  /** Pages to exhaustion, storing the cursor as JSON TEXT between chunks exactly as the jsonb column would. */
  private static List<Map<String, Object>> drain(JdbcChunkReader reader, int chunk) throws Exception {
    ConnectorCursor cursor = ConnectorCursor.empty();
    List<Map<String, Object>> rows = new ArrayList<>();
    ReadResult<Map<String, Object>> r;
    int guard = 0;
    while ((r = reader.read(chunk, cursor, null)) instanceof ReadResult.HasMore<Map<String, Object>> hm) {
      rows.addAll(hm.records());
      cursor = new ConnectorCursor(M.readTree(M.writeValueAsString(hm.nextCursor().value())), Instant.now());
      if (++guard > 200) throw new AssertionError("runaway paging loop");
    }
    return rows;
  }
}
