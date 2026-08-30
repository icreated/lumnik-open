package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.SqlLexer;
import org.junit.jupiter.api.Test;

import java.util.List;

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
}
