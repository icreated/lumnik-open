package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.SqlLexer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

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
  void skips_qualified_and_cast_columns() {
    assertThat(filters("WHERE t.col = 'x'")).isEmpty();
    assertThat(filters("WHERE foo::text = 'x'")).isEmpty();
  }

  /**
   * Quoted used to sit in the list above, and the reason was written down: the card advertised bare
   * names. It advertises quoted ones now — the only form a model can copy for a column called
   * {@code c_bpartner.id} — so a quoted name is read, and the filter carries the UNQUOTED text the
   * guards compare against their declared set.
   */
  @Test
  void readsAQuotedColumnAndHandsOverItsUnquotedName() {
    assertThat(filters("WHERE \"col\" = 'x'")).singleElement()
        .extracting(SqlFilters.Filter::column).isEqualTo("col");
    assertThat(filters("WHERE \"Partner Name\" = 'x'")).singleElement()
        .extracting(SqlFilters.Filter::column).isEqualTo("Partner Name");
  }

  /**
   * {@code not} is excluded because the logical operator lexes as an identifier — true of the BARE one
   * only. Quoted, it is an ordinary column name to Postgres, and the card now hands the model every
   * name quoted, so the exclusion would silently skip a real filter. The correct shape was two methods
   * away in this same file: {@code isKeyword} has always tested {@code !t.quoted()}.
   */
  @Test
  void aQuotedKeywordIsAColumnName_whileTheBareOperatorStaysExcluded() {
    assertThat(filters("WHERE \"not\" = 'x'")).singleElement()
        .extracting(SqlFilters.Filter::column).isEqualTo("not");
    assertThat(filters("WHERE segment NOT IN ('A','B')")).singleElement()
        .extracting(SqlFilters.Filter::column).isEqualTo("segment");
  }

  @Test
  void skips_a_function_wrapped_column_by_adjacency() {
    assertThat(filters("WHERE lower(col) = 'x'")).isEmpty();
  }

  @Test
  void skips_a_numeric_rhs() {
    assertThat(filters("WHERE col = 5")).isEmpty();
  }

  // ── #1338: a number literal, for the guards that read a number column ───────

  private static List<SqlFilters.Filter> numbers(String sql) {
    return SqlFilters.columnNumberFilters(SqlLexer.tokenize(sql));
  }

  @Test
  void a_number_filter_is_read_in_its_canonical_text() {
    assertThat(numbers("WHERE code = 5")).singleElement()
        .extracting(SqlFilters.Filter::column, SqlFilters.Filter::values).containsExactly("code", List.of("5"));
    assertThat(numbers("WHERE code = 5.0")).singleElement()
        .extracting(SqlFilters.Filter::values).isEqualTo(List.of("5"));
    assertThat(numbers("WHERE amount <> 12.50")).singleElement()
        .extracting(SqlFilters.Filter::values).isEqualTo(List.of("12.5"));
    assertThat(numbers("WHERE code IN (1, 2.0, 100)")).singleElement()
        .extracting(SqlFilters.Filter::values).isEqualTo(List.of("1", "2", "100"));
    assertThat(numbers("WHERE code NOT IN (7)")).singleElement()
        .extracting(SqlFilters.Filter::values).isEqualTo(List.of("7"));
  }

  /**
   * The literal is the model's, so a user's question can put any number there. An exponent bomb must not become a
   * billion-digit string (toPlainString): only a plain decimal within 38 digits each side is read; anything else is
   * null — the guards' fail-open, never an OutOfMemoryError. (The lexer splits {@code 1e9} into {@code 1} and a name,
   * so the bomb reaches this only as a quoted string: DisputedRowsPlanTest pins that path.)
   */
  @ParameterizedTest
  @ValueSource(strings = {"1e999999999", "1E-999999999", "9e2147483647",
      "1.5e3", "1234567890123456789012345678901234567890"})
  void a_number_that_is_not_a_bounded_plain_decimal_is_not_read(String literal) {
    assertThat(assertTimeoutPreemptively(Duration.ofSeconds(1),
        () -> SqlFilters.canonicalNumber(literal))).isNull();
  }

  @Test
  void a_number_reader_skips_strings_mixed_lists_and_the_string_reader_skips_numbers() {
    assertThat(numbers("WHERE code = '5'")).isEmpty();
    assertThat(numbers("WHERE code IN (1, '2')")).isEmpty();
    assertThat(numbers("WHERE lower(code) = 5")).isEmpty();
    assertThat(filters("WHERE code IN (1, 2)")).isEmpty();
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
