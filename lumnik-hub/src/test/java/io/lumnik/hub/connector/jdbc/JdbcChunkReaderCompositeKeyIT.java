package io.lumnik.hub.connector.jdbc;

import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.hub.connector.jdbc.dialect.PostgresDialect;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A composite primary key pages on the WHOLE key. Reading only its first column loses every row
 * of the group that straddles a chunk boundary: the chunk stops mid-group and the next one asks
 * for {@code first > lastValue}, skipping the rest of that group. Silently — the rows are never
 * read, so nothing is skipped, dead-lettered or counted.
 *
 * <p>Measured on the live demo source before the fix: 700 rows keyed {@code (order_id, line_no)},
 * 7 lines per order, chunk 500 → 696 ingested, IN=696, SKIPPED=0, run Completed.
 */
@Testcontainers
class JdbcChunkReaderCompositeKeyIT {

  @Container
  static PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("s").withUsername("u").withPassword("p");

  static final HikariPoolRegistry REGISTRY = new HikariPoolRegistry();
  static final int ORDERS = 100;
  static final int LINES_PER_ORDER = 7;
  static final int ROWS = ORDERS * LINES_PER_ORDER;

  @BeforeAll
  static void seed() throws Exception {
    try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "u", "p");
         var st = c.createStatement()) {
      st.execute("CREATE TABLE order_lines (order_id BIGINT, line_no INT, sku TEXT,"
          + " updated TIMESTAMP NOT NULL, PRIMARY KEY (order_id, line_no))");
      // All lines of an order share one `updated`: the watermark alone cannot separate them,
      // so the tiebreak has to carry the whole key.
      for (int o = 1; o <= ORDERS; o++) {
        for (int l = 1; l <= LINES_PER_ORDER; l++) {
          st.execute("INSERT INTO order_lines VALUES (" + o + ", " + l + ", 'sku" + l + "',"
              + " TIMESTAMP '2026-01-01 00:00:00' + (interval '1 second' * " + o + "))");
        }
      }
    }
  }

  @AfterAll
  static void tearDown() {
    REGISTRY.evictAll();
  }

  // chunk 500 stops at order 72 line 3 — the historical loss of 4 rows; chunk 30 straddles a
  // group at every boundary; chunk 7 lands exactly on one, which must NOT drop the next group.
  @ParameterizedTest(name = "{0}, chunk {1}")
  @CsvSource({"WATERMARK, 500", "WATERMARK, 30", "WATERMARK, 7",
              "FULL, 500", "FULL, 30", "FULL, 7"})
  void every_row_of_a_composite_key_table_is_read(JdbcChunkReader.Mode mode, int chunk) {
    JdbcChunkReader reader =
        reader(mode == JdbcChunkReader.Mode.WATERMARK ? "updated" : null, mode);

    List<Map<String, Object>> rows = drain(reader, ConnectorCursor.empty(), chunk);
    Set<String> keys = new LinkedHashSet<>();
    for (Map<String, Object> row : rows) {
      keys.add(row.get("order_id") + "-" + row.get("line_no"));
    }

    assertEquals(ROWS, rows.size(), "no row of the source is left behind by a chunk boundary");
    assertEquals(ROWS, keys.size(), "and none is read twice");
  }

  /**
   * The cursor an earlier build persisted for a composite key holds only its first column. It is
   * not a position on the whole key — resuming from it lands past the group it stopped inside —
   * so it is foreign: the reader ignores it, WARNs, and the table restarts.
   */
  @Test
  void a_cursor_holding_only_the_first_key_column_is_foreign() {
    ConnectorCursor firstColumnOnly =
        new JdbcCursor("", null, Map.of("order_id", 72L)).toConnectorCursor();

    assertEquals(ROWS, drain(reader(null, JdbcChunkReader.Mode.FULL), firstColumnOnly, 500).size(),
        "a one-column cursor is not a position on a two-column key: restart, do not resume");
  }

  /** The key columns are ordered: the same names in another order page differently. */
  @Test
  void a_cursor_keyed_on_the_same_columns_in_another_order_is_foreign() {
    Map<String, Object> reversed = new LinkedHashMap<>();
    reversed.put("line_no", 3);
    reversed.put("order_id", 72L);
    ConnectorCursor otherOrder = new JdbcCursor("", null, reversed).toConnectorCursor();

    assertEquals(ROWS, drain(reader(null, JdbcChunkReader.Mode.FULL), otherOrder, 500).size(),
        "pk_columns order is the paging order — reversing it is another configuration");
  }

  private static JdbcChunkReader reader(String watermarkColumn, JdbcChunkReader.Mode mode) {
    return new JdbcChunkReader(PG.getJdbcUrl(), "u", "p", "postgres",
        "public", "order_lines", watermarkColumn, List.of("order_id", "line_no"),
        mode, new PostgresDialect(), REGISTRY);
  }

  /** Reads to exhaustion from {@code start}, returning every row the HasMore chunks carried. */
  private static List<Map<String, Object>> drain(JdbcChunkReader reader, ConnectorCursor start, int chunk) {
    ConnectorCursor cursor = start;
    List<Map<String, Object>> rows = new ArrayList<>();
    ReadResult<Map<String, Object>> r;
    while ((r = reader.read(chunk, cursor, null)) instanceof ReadResult.HasMore<Map<String, Object>> hm) {
      rows.addAll(hm.records());
      cursor = hm.nextCursor();
    }
    return rows;
  }
}
