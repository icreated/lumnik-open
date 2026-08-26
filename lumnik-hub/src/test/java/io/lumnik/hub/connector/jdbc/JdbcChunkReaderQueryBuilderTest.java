package io.lumnik.hub.connector.jdbc;

import io.lumnik.hub.connector.jdbc.dialect.PostgresDialect;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JdbcChunkReaderQueryBuilderTest {

  private final JdbcQueryBuilder qb = new JdbcQueryBuilder(new PostgresDialect());

  @Test
  void watermark_mode_empty_cursor_emits_only_order_and_limit() {
    String sql = qb.buildSelect("public", "c_bpartner", "updated",
        List.of("c_bpartner_id"), JdbcCursor.empty(), 500, JdbcQueryBuilder.Mode.WATERMARK);
    assertTrue(sql.startsWith("SELECT * FROM \"public\".\"c_bpartner\""));
    assertFalse(sql.contains("WHERE"));
    assertTrue(sql.contains("ORDER BY \"updated\" ASC, \"c_bpartner_id\" ASC"));
    assertTrue(sql.contains("LIMIT 500"));
  }

  @Test
  void watermark_mode_non_empty_cursor_emits_where_with_tiebreak() {
    JdbcCursor cursor = new JdbcCursor("updated", "2026-05-23T10:00:00Z",
        Map.of("c_bpartner_id", 42L));
    String sql = qb.buildSelect("public", "c_bpartner", "updated",
        List.of("c_bpartner_id"), cursor, 500, JdbcQueryBuilder.Mode.WATERMARK);
    assertTrue(sql.contains("WHERE"));
    assertTrue(sql.contains("\"updated\" > ?"));
    assertTrue(sql.contains("OR (\"updated\" = ? AND \"c_bpartner_id\" > ?)"));
  }

  @Test
  void full_mode_no_where_uses_pk_ordering_only() {
    String sql = qb.buildSelect("public", "ref_status", null,
        List.of("id"), JdbcCursor.empty(), 500, JdbcQueryBuilder.Mode.FULL);
    assertFalse(sql.contains("WHERE"));
    assertTrue(sql.contains("ORDER BY \"id\" ASC"));
  }

  @Test
  void full_mode_with_cursor_paginates_by_pk_only() {
    JdbcCursor cursor = new JdbcCursor("", null, Map.of("id", 100L));
    String sql = qb.buildSelect("public", "ref_status", null,
        List.of("id"), cursor, 500, JdbcQueryBuilder.Mode.FULL);
    assertTrue(sql.contains("WHERE \"id\" > ?"));
    assertTrue(sql.contains("ORDER BY \"id\" ASC"));
  }

  @Test
  void identifiers_are_always_quoted_safely() {
    String sql = qb.buildSelect("weird-schema", "weird\"name", "updated",
        List.of("pk"), JdbcCursor.empty(), 10, JdbcQueryBuilder.Mode.WATERMARK);
    assertTrue(sql.contains("\"weird-schema\""));
    assertTrue(sql.contains("\"weird\"\"name\""));
  }
}
