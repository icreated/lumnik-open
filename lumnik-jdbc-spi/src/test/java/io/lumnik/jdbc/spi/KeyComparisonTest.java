package io.lumnik.jdbc.spi;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyComparisonTest {

  @Test
  void a_row_value_plan_over_two_columns_is_valid() {
    KeyComparison kc = new KeyComparison("(a, b) > (?, ?)", List.of(0, 1));
    assertThat(kc.validateFor(2)).isSameAs(kc);
  }

  @Test
  void an_expanded_plan_may_repeat_a_column() {
    assertThat(new KeyComparison("((a > ?) OR (a = ? AND b > ?))", List.of(0, 0, 1)).validateFor(2)).isNotNull();
  }

  @Test
  void an_or_expansion_without_its_outer_parentheses_is_refused() {
    // WHERE <this> AND <ceiling> would bind the AND to the last disjunct only: the ceiling is escaped.
    assertThatThrownBy(() -> new KeyComparison("(\"a\" > ?) OR (\"a\" = ? AND \"b\" > ?)", List.of(0, 0, 1)).validateFor(2))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("parenthes");
  }

  @Test
  void an_or_expansion_wrapped_in_parentheses_is_accepted() {
    assertThat(new KeyComparison("((\"a\" > ?) OR (\"a\" = ? AND \"b\" > ?))", List.of(0, 0, 1)).validateFor(2)).isNotNull();
  }

  @Test
  void the_word_or_inside_a_quoted_identifier_or_a_longer_word_is_not_a_top_level_or() {
    assertThat(new KeyComparison("(\"x OR y\", \"order\") > (?, ?)", List.of(0, 1)).validateFor(2)).isNotNull();
    assertThat(new KeyComparison("\"color\" > ?", List.of(0)).validateFor(1)).isNotNull();
  }

  @Test
  void a_stray_closing_parenthesis_cannot_hide_a_top_level_or() {
    // depth would go to -1 at the first ')' and the OR would never be seen at depth 0
    assertThatThrownBy(() -> new KeyComparison("a > ?) OR (b > ?", List.of(0, 1)).validateFor(2))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("unbalanced");
  }

  @Test
  void an_unclosed_parenthesis_is_refused() {
    assertThatThrownBy(() -> new KeyComparison("((a > ?)", List.of(0)).validateFor(1))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("unbalanced");
  }

  @Test
  void an_unterminated_quote_is_refused() {
    assertThatThrownBy(() -> new KeyComparison("(\"a > ?)", List.of(0)).validateFor(1))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("unbalanced");
  }

  @Test
  void null_sql_is_refused() {
    assertThatThrownBy(() -> new KeyComparison(null, List.of(0)).validateFor(1))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("no SQL");
  }

  @Test
  void blank_sql_is_refused() {
    assertThatThrownBy(() -> new KeyComparison("  ", List.of(0)).validateFor(1))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("no SQL");
  }

  @Test
  void a_null_plan_is_refused() {
    assertThatThrownBy(() -> new KeyComparison("a > ?", null).validateFor(1))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("no bind plan");
  }

  @Test
  void an_empty_plan_over_columns_is_refused() {
    assertThatThrownBy(() -> new KeyComparison("(a, b) > (?, ?)", List.of()).validateFor(2))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("binds nothing");
  }

  @Test
  void a_negative_index_is_refused() {
    assertThatThrownBy(() -> new KeyComparison("a > ?", List.of(-1)).validateFor(1))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("-1");
  }

  @Test
  void an_index_past_the_last_column_is_refused() {
    assertThatThrownBy(() -> new KeyComparison("(a, b) > (?, ?)", List.of(0, 2)).validateFor(2))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("2");
  }

  @Test
  void a_null_index_is_refused() {
    assertThatThrownBy(() -> new KeyComparison("a > ?", Arrays.asList((Integer) null)).validateFor(1))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("null");
  }
}
