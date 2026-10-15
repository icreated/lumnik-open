package io.lumnik.hub.connector.jdbc;

import io.lumnik.jdbc.postgresql.PostgresDialect;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The exact SQL the PostgreSQL dialect produces today — the byte-identity guarantee of #857. */
class JdbcQueryBuilderSqlPinTest {

  private final JdbcQueryBuilder qb = new JdbcQueryBuilder(new PostgresDialect());
  private static final JdbcCursor CURSOR = new JdbcCursor("u", "2026-01-01T00:00:00Z", Map.of("id", 1L));
  private static final JdbcRunBound WATERMARK_BOUND =
      new JdbcRunBound(List.of("u", "id"), List.of("2026-02-01", 9L));

  @Test
  void watermark_with_cursor_and_ceiling() {
    assertEquals("SELECT * FROM \"s\".\"t\" WHERE (\"u\", \"id\") > (?, ?) "
        + "AND ((\"u\", \"id\") <= (?, ?) OR \"u\" IS NULL) ORDER BY \"u\" ASC, \"id\" ASC LIMIT 100",
        qb.buildSelect("s", "t", "u", List.of("id"), CURSOR, WATERMARK_BOUND, 100,
            JdbcQueryBuilder.Mode.WATERMARK).sql());
  }

  @Test
  void full_cursorless_unbounded() {
    assertEquals("SELECT * FROM \"s\".\"t\" ORDER BY \"id\" ASC LIMIT 50",
        qb.buildSelect("s", "t", null, List.of("id"), JdbcCursor.empty(), null, 50,
            JdbcQueryBuilder.Mode.FULL).sql());
  }

  @Test
  void full_with_ceiling_has_no_null_escape() {
    assertEquals("SELECT * FROM \"s\".\"t\" WHERE (\"id\") <= (?) ORDER BY \"id\" ASC LIMIT 5",
        qb.buildSelect("s", "t", null, List.of("id"), JdbcCursor.empty(),
            new JdbcRunBound(List.of("id"), List.of(9L)), 5, JdbcQueryBuilder.Mode.FULL).sql());
  }

  @Test
  void ceiling_probe_watermark_and_full() {
    assertEquals("SELECT \"u\", \"id\" FROM \"s\".\"t\" WHERE \"u\" IS NOT NULL "
        + "ORDER BY \"u\" DESC, \"id\" DESC LIMIT 1",
        qb.buildCeilingProbe("s", "t", "u", List.of("id"), JdbcQueryBuilder.Mode.WATERMARK));
    assertEquals("SELECT \"id\" FROM \"s\".\"t\" ORDER BY \"id\" DESC LIMIT 1",
        qb.buildCeilingProbe("s", "t", null, List.of("id"), JdbcQueryBuilder.Mode.FULL));
  }
}
