package io.lumnik.hub.connector.jdbc;

import io.lumnik.jdbc.postgresql.PostgresDialect;
import io.lumnik.jdbc.spi.Comparison;
import io.lumnik.jdbc.spi.KeyComparison;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The builder takes the key comparison and the limit from the dialect and hands back the bind plans. */
class JdbcQueryBuilderPlanTest {

  private static final JdbcCursor CURSOR = new JdbcCursor("u", "2026-01-01T00:00:00Z", Map.of("id", 1L));
  private static final JdbcRunBound BOUND = new JdbcRunBound(List.of("u", "id"), List.of("x", 9L));

  /** PostgreSQL for everything but the two fragments, which expand the tuple like Oracle would. */
  private static class ExpandingDialect extends PostgresDialect {
    @Override
    public KeyComparison keyComparison(List<String> c, Comparison cmp) {
      String op = cmp == Comparison.GREATER_THAN ? ">" : "<=";
      // ((a first ?) OR (a = ? AND b op ?)) — the first column repeats, so the plan repeats index 0.
      // The leading comparison is STRICT for "<=": (a <= ?) alone would admit every b of the tie group.
      String first = cmp == Comparison.GREATER_THAN ? ">" : "<";
      if (c.size() == 1) return new KeyComparison("(" + c.get(0) + " " + op + " ?)", List.of(0));
      return new KeyComparison("((" + c.get(0) + " " + first + " ?) OR (" + c.get(0) + " = ? AND "
          + c.get(1) + " " + op + " ?))", List.of(0, 0, 1));
    }

    @Override
    public String limitClause(int limit) { return "FETCH FIRST " + limit + " ROWS ONLY"; }
  }

  @Test
  void an_expanding_dialect_gets_its_sql_its_plan_and_its_limit() {
    JdbcQueryBuilder qb = new JdbcQueryBuilder(new ExpandingDialect());
    JdbcQueryBuilder.Select sel = qb.buildSelect("s", "t", "u", List.of("id"), CURSOR, null, 100,
        JdbcQueryBuilder.Mode.WATERMARK);
    assertEquals("SELECT * FROM \"s\".\"t\" WHERE ((\"u\" > ?) OR (\"u\" = ? AND \"id\" > ?)) "
        + "ORDER BY \"u\" ASC, \"id\" ASC FETCH FIRST 100 ROWS ONLY", sel.sql());
    assertEquals(List.of(0, 0, 1), sel.cursorPlan());
    assertEquals(List.of(), sel.ceilingPlan());
  }

  @Test
  void the_watermark_ceiling_keeps_its_null_escape_around_the_dialects_expansion() {
    JdbcQueryBuilder qb = new JdbcQueryBuilder(new ExpandingDialect());
    JdbcQueryBuilder.Select sel = qb.buildSelect("s", "t", "u", List.of("id"), JdbcCursor.empty(), BOUND, 10,
        JdbcQueryBuilder.Mode.WATERMARK);
    assertTrue(sel.sql().contains("WHERE (((\"u\" < ?) OR (\"u\" = ? AND \"id\" <= ?)) OR \"u\" IS NULL)"), sel.sql());
    assertEquals(List.of(0, 0, 1), sel.ceilingPlan());
    assertEquals(List.of(), sel.cursorPlan());
  }

  @Test
  void the_ceiling_probe_takes_its_limit_from_the_dialect() {
    JdbcQueryBuilder qb = new JdbcQueryBuilder(new ExpandingDialect());
    assertTrue(qb.buildCeilingProbe("s", "t", null, List.of("id"), JdbcQueryBuilder.Mode.FULL)
        .endsWith("ORDER BY \"id\" DESC FETCH FIRST 1 ROWS ONLY"));
  }

  @Test
  void the_row_value_dialect_binds_each_key_once_in_order() {
    JdbcQueryBuilder qb = new JdbcQueryBuilder(new PostgresDialect());
    JdbcQueryBuilder.Select sel = qb.buildSelect("s", "t", "u", List.of("id"), CURSOR, BOUND, 10,
        JdbcQueryBuilder.Mode.WATERMARK);
    assertEquals(List.of(0, 1), sel.cursorPlan());
    assertEquals(List.of(0, 1), sel.ceilingPlan());
  }

  @Test
  void a_plan_naming_a_key_that_does_not_exist_makes_the_builder_throw_instead_of_emit() {
    PostgresDialect broken = new PostgresDialect() {
      @Override
      public KeyComparison keyComparison(List<String> c, Comparison cmp) {
        return new KeyComparison("(" + String.join(", ", c) + ") > (?, ?)", List.of(0, 5));
      }
    };
    JdbcQueryBuilder qb = new JdbcQueryBuilder(broken);
    assertThrows(IllegalStateException.class, () -> qb.buildSelect("s", "t", "u", List.of("id"), CURSOR, null, 10,
        JdbcQueryBuilder.Mode.WATERMARK));
  }
}
