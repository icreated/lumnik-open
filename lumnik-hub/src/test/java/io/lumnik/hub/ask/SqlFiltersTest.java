package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.SqlLexer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SqlFiltersTest {

  private static List<SqlFilters.Filter> filters(String sql) {
    return SqlFilters.columnLiteralFilters(SqlLexer.tokenize(sql));
  }

  @Test
  void extracts_an_equality_filter() {
    assertThat(filters("WHERE col = 'x'")).singleElement()
        .extracting(SqlFilters.Filter::column, SqlFilters.Filter::values)
        .containsExactly("col", List.of("x"));
  }

  @Test
  void extracts_not_equal_filters() {
    assertThat(filters("WHERE col <> 'x'")).hasSize(1);
    assertThat(filters("WHERE col != 'x'")).hasSize(1);
  }

  @Test
  void extracts_a_clean_in_list() {
    assertThat(filters("WHERE col IN ('a','b')")).singleElement()
        .extracting(SqlFilters.Filter::values).isEqualTo(List.of("a", "b"));
  }

  @Test
  void skips_qualified_quoted_and_cast_columns() {
    assertThat(filters("WHERE t.col = 'x'")).isEmpty();
    assertThat(filters("WHERE \"col\" = 'x'")).isEmpty();
    assertThat(filters("WHERE foo::text = 'x'")).isEmpty();
  }

  @Test
  void skips_a_function_wrapped_column_by_adjacency() {
    assertThat(filters("WHERE lower(col) = 'x'")).isEmpty();
  }

  @Test
  void skips_a_numeric_rhs() {
    assertThat(filters("WHERE col = 5")).isEmpty();
  }

  @Test
  void skips_a_non_clean_in_list() {
    assertThat(filters("WHERE col IN ('a', upper('b'))")).isEmpty();
  }

  @Test
  void inspects_a_bare_parenthesized_filter() {
    // the documented divergence resolution: a bare parenthesized filter IS extracted (no `(`-exclusion)
    assertThat(filters("WHERE (col = 'x')")).singleElement()
        .extracting(SqlFilters.Filter::column).isEqualTo("col");
  }

  // ── the scalar subquery a disclosure re-runs ────────────────────────────────

  /**
   * The shapes a generated filter actually takes. Extraction feeds a message and never a decision,
   * so every unrecognised shape must come back empty rather than wrong — as many must-NOT-match
   * rows as matches. Three are not hypothetical: {@code = ( SELECT} with a space is what a live
   * qwen2.5-coder emits, and the two comment rows are the blind spot a hand-rolled scanner had
   * before this went through {@link SqlLexer} — it read {@code -- id = (SELECT …)} as code.
   */
  /** One row of the table: what the SQL is, and what the extraction must say about it. */
  private record Shape(String name, String sql, String expected) {
    @Override public String toString() { return name; }
  }

  static Stream<Arguments> shapes() {
    return Stream.of(
      new Shape("the shape that prompted this fix",
        "SELECT count(*) FROM ext.c_order WHERE c_bpartner_id = "
          + "(SELECT c_bpartner_id FROM ext.c_bpartner WHERE name = 'ACME') LIMIT 500",
        "SELECT c_bpartner_id FROM ext.c_bpartner WHERE name = 'ACME'"),
      new Shape("a space between = and the paren, as the model actually writes it",
        "SELECT 1 FROM t WHERE id = ( SELECT id FROM p WHERE name = 'ACME' )",
        "SELECT id FROM p WHERE name = 'ACME'"),
      new Shape("keywords in lower case",
        "select 1 from t where id = (select id from p where name = 'ACME')",
        "select id from p where name = 'ACME'"),
      new Shape("parentheses nested inside the subquery stay inside it",
        "SELECT 1 FROM t WHERE id = (SELECT id FROM p WHERE k IN (1, 2))",
        "SELECT id FROM p WHERE k IN (1, 2)"),
      new Shape("a closing paren inside a literal must not end the fragment early",
        "SELECT 1 FROM t WHERE id = (SELECT id FROM p WHERE name = 'a)b')",
        "SELECT id FROM p WHERE name = 'a)b'"),
      new Shape("a doubled quote is an escaped quote, not the end of the literal",
        "SELECT 1 FROM t WHERE id = (SELECT id FROM p WHERE name = 'O''Brien')",
        "SELECT id FROM p WHERE name = 'O''Brien'"),
      new Shape("an = inside a literal is not the operator we are looking for",
        "SELECT 1 FROM t WHERE label = 'x=(y' AND id = (SELECT id FROM p)",
        "SELECT id FROM p"),

      // Must NOT match — extraction is fail-open, and a wrong guess would be worse than none.
      new Shape("IN is already the shape that survives duplicates",
        "SELECT 1 FROM t WHERE id IN (SELECT id FROM p WHERE name = 'ACME')", null),
      new Shape("a plain literal comparison is not a subquery",
        "SELECT 1 FROM t WHERE id = 42", null),
      new Shape("parenthesised arithmetic is not a subquery",
        "SELECT 1 FROM t WHERE total = (a + b)", null),
      new Shape("an unbalanced paren yields nothing rather than a truncated fragment",
        "SELECT 1 FROM t WHERE id = (SELECT id FROM p", null),
      new Shape("a join carries no scalar subquery",
        "SELECT count(*) FROM ext.c_order o JOIN ext.c_bpartner c "
          + "ON o.c_bpartner_id = c.c_bpartner_id WHERE c.name = 'ACME'", null),
      new Shape("an unterminated literal cannot be mined for one either",
        "SELECT 1 FROM t WHERE name = 'oops AND id = (SELECT id FROM p)", null),
      new Shape("a line comment is not code — SqlGuard lets a trailing -- through, and the "
        + "appended LIMIT is placed on its own line precisely because of it",
        "SELECT 1 FROM t WHERE id = 42 -- id = (SELECT id FROM p)", null),
      new Shape("a block comment is not code either",
        "SELECT 1 FROM t /* id = (SELECT id FROM p) */ WHERE id = 42", null),
      new Shape("no sql at all", null, null),
      new Shape("empty sql", "", null)
    ).map(Arguments::of);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("shapes")
  void the_scalar_subquery_is_extracted_only_when_it_is_unmistakable(Shape shape) {
    var found = SqlFilters.scalarSubquery(shape.sql());
    if (shape.expected() == null) {
      assertThat(found).as("must stay empty — a wrong fragment is worse than no message").isEmpty();
    } else {
      assertThat(found).contains(shape.expected());
    }
  }

}
