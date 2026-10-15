package io.lumnik.hub.connector.jdbc;

import io.lumnik.jdbc.postgresql.PostgresDialect;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JdbcChunkReaderQueryBuilderTest {

  private final JdbcQueryBuilder qb = new JdbcQueryBuilder(new PostgresDialect());

  @Test
  void watermark_mode_empty_cursor_emits_only_order_and_limit() {
    String sql = qb.buildSelect("public", "c_bpartner", "updated",
        List.of("c_bpartner_id"), JdbcCursor.empty(), null, 500, JdbcQueryBuilder.Mode.WATERMARK).sql();
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
        List.of("c_bpartner_id"), cursor, null, 500, JdbcQueryBuilder.Mode.WATERMARK).sql();
    assertTrue(sql.contains("WHERE (\"updated\", \"c_bpartner_id\") > (?, ?)"), sql);
    assertTrue(sql.contains("ORDER BY \"updated\" ASC, \"c_bpartner_id\" ASC"), sql);
  }

  @Test
  void full_mode_no_where_uses_pk_ordering_only() {
    String sql = qb.buildSelect("public", "ref_status", null,
        List.of("id"), JdbcCursor.empty(), null, 500, JdbcQueryBuilder.Mode.FULL).sql();
    assertFalse(sql.contains("WHERE"));
    assertTrue(sql.contains("ORDER BY \"id\" ASC"));
  }

  @Test
  void full_mode_with_cursor_paginates_by_pk_only() {
    JdbcCursor cursor = new JdbcCursor("", null, Map.of("id", 100L));
    String sql = qb.buildSelect("public", "ref_status", null,
        List.of("id"), cursor, null, 500, JdbcQueryBuilder.Mode.FULL).sql();
    assertTrue(sql.contains("WHERE (\"id\") > (?)"), sql);
    assertTrue(sql.contains("ORDER BY \"id\" ASC"), sql);
  }

  @Test
  void full_mode_composite_key_pages_on_every_column() {
    JdbcCursor cursor = new JdbcCursor("", null,
        new LinkedHashMap<>(Map.of("order_id", 72L, "line_no", 3)));
    String sql = qb.buildSelect("demo", "order_lines", null,
        List.of("order_id", "line_no"), cursor, null, 500, JdbcQueryBuilder.Mode.FULL).sql();
    assertTrue(sql.contains("WHERE (\"order_id\", \"line_no\") > (?, ?)"), sql);
    assertTrue(sql.contains("ORDER BY \"order_id\" ASC, \"line_no\" ASC"), sql);
  }

  @Test
  void watermark_mode_composite_key_extends_the_tiebreak_to_the_whole_key() {
    JdbcCursor cursor = new JdbcCursor("updated", "2026-05-23 10:00:00",
        new LinkedHashMap<>(Map.of("order_id", 72L, "line_no", 3)));
    String sql = qb.buildSelect("demo", "order_lines", "updated",
        List.of("order_id", "line_no"), cursor, null, 500, JdbcQueryBuilder.Mode.WATERMARK).sql();
    assertTrue(sql.contains("WHERE (\"updated\", \"order_id\", \"line_no\") > (?, ?, ?)"), sql);
    assertTrue(sql.contains("ORDER BY \"updated\" ASC, \"order_id\" ASC, \"line_no\" ASC"), sql);
  }

  @Test
  void composite_key_without_a_cursor_still_orders_on_the_whole_key() {
    String sql = qb.buildSelect("demo", "order_lines", null,
        List.of("order_id", "line_no"), JdbcCursor.empty(), null, 500, JdbcQueryBuilder.Mode.FULL).sql();
    assertFalse(sql.contains("WHERE"), sql);
    assertTrue(sql.contains("ORDER BY \"order_id\" ASC, \"line_no\" ASC"), sql);
  }

  @Test
  void identifiers_are_always_quoted_safely() {
    String sql = qb.buildSelect("weird-schema", "weird\"name", "updated",
        List.of("pk"), JdbcCursor.empty(), null, 10, JdbcQueryBuilder.Mode.WATERMARK).sql();
    assertTrue(sql.contains("\"weird-schema\""));
    assertTrue(sql.contains("\"weird\"\"name\""));
  }

  // ── the ceiling: a run drains what the table held when it started ──
  //
  // Without one this query pages on (keys) > (last) for as long as the writers keep writing: the
  // run ends when THEY pause. The same shape kafka had, found by asking who else paged without a
  // ceiling.

  @Test
  void ceiling_adds_a_second_row_comparison_over_the_same_keys() {
    JdbcCursor cursor = new JdbcCursor("updated", "2026-05-23T10:00:00Z",
        Map.of("c_bpartner_id", 42L));
    JdbcRunBound bound = new JdbcRunBound(List.of("updated", "c_bpartner_id"),
        List.of("2026-09-12T08:14:22Z", 900L));

    String sql = qb.buildSelect("public", "c_bpartner", "updated",
        List.of("c_bpartner_id"), cursor, bound, 500, JdbcQueryBuilder.Mode.WATERMARK).sql();

    assertTrue(sql.contains("WHERE (\"updated\", \"c_bpartner_id\") > (?, ?)"
        + " AND ((\"updated\", \"c_bpartner_id\") <= (?, ?) OR \"updated\" IS NULL)"), sql);
    assertTrue(sql.contains("ORDER BY \"updated\" ASC, \"c_bpartner_id\" ASC"), sql);
  }

  @Test
  void ceiling_is_the_only_clause_on_a_first_run() {
    JdbcRunBound bound = new JdbcRunBound(List.of("updated", "id"), List.of("x", 1L));

    String sql = qb.buildSelect("public", "t", "updated", List.of("id"),
        JdbcCursor.empty(), bound, 500, JdbcQueryBuilder.Mode.WATERMARK).sql();

    assertTrue(sql.contains("WHERE ((\"updated\", \"id\") <= (?, ?) OR \"updated\" IS NULL)"), sql);
    assertFalse(sql.contains(") > ("), sql);
  }

  @Test
  void no_ceiling_leaves_the_query_exactly_as_it_was() {
    JdbcCursor cursor = new JdbcCursor("updated", "v", Map.of("id", 7L));
    String bounded = qb.buildSelect("public", "t", "updated", List.of("id"),
        cursor, null, 500, JdbcQueryBuilder.Mode.WATERMARK).sql();
    assertTrue(bounded.contains("WHERE (\"updated\", \"id\") > (?, ?) ORDER BY"), bounded);
  }

  @Test
  void ceiling_probe_takes_the_greatest_key_row_and_refuses_a_null_watermark() {
    // Postgres orders NULLs FIRST on a DESC sort. Without IS NOT NULL the greatest key row of a
    // table holding one null watermark would BE that null, every (keys) <= (NULL, …) would be
    // unknown, and the run would read nothing at all.
    String probe = qb.buildCeilingProbe("public", "c_bpartner", "updated",
        List.of("c_bpartner_id"), JdbcQueryBuilder.Mode.WATERMARK);

    assertEquals("SELECT \"updated\", \"c_bpartner_id\" FROM \"public\".\"c_bpartner\""
        + " WHERE \"updated\" IS NOT NULL"
        + " ORDER BY \"updated\" DESC, \"c_bpartner_id\" DESC LIMIT 1", probe);
  }

  @Test
  void ceiling_probe_in_full_mode_pages_on_the_primary_key_and_needs_no_null_clause() {
    String probe = qb.buildCeilingProbe("public", "ref_status", null,
        List.of("id"), JdbcQueryBuilder.Mode.FULL);

    assertEquals("SELECT \"id\" FROM \"public\".\"ref_status\""
        + " ORDER BY \"id\" DESC LIMIT 1", probe);
    assertFalse(probe.contains("IS NOT NULL"), "a primary key column cannot be null");
  }

  @Test
  void a_ceiling_needs_one_value_per_key_column() {
    assertThrows(IllegalArgumentException.class,
        () -> new JdbcRunBound(List.of("updated", "id"), List.of("only-one")));
  }

  @Test
  void the_ceiling_keeps_the_rows_that_have_no_watermark() {
    // A row-wise comparison with a NULL left operand is NULL, so `(wm, pk) <= (?, ?)` alone drops
    // every row with no watermark — rows a cursorless first pass has always read, since Postgres
    // sorts NULL LAST on an ASC order. Measured before this clause existed: 10 of 12 rows,
    // `Completed`, no dead letter, no log line.
    JdbcRunBound bound = new JdbcRunBound(List.of("updated", "id"), List.of("x", 1L));

    String watermark = qb.buildSelect("public", "t", "updated", List.of("id"),
        JdbcCursor.empty(), bound, 500, JdbcQueryBuilder.Mode.WATERMARK).sql();
    assertTrue(watermark.contains("OR \"updated\" IS NULL"), watermark);

    // FULL mode pages on the primary key, which cannot be null — no such clause, and none wanted.
    String full = qb.buildSelect("public", "t", null, List.of("id"),
        JdbcCursor.empty(), new JdbcRunBound(List.of("id"), List.of(1L)), 500,
        JdbcQueryBuilder.Mode.FULL).sql();
    assertTrue(full.contains("WHERE (\"id\") <= (?)"), full);
    assertFalse(full.contains("IS NULL"), full);
  }
}
