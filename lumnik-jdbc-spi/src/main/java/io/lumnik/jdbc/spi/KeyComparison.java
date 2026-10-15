package io.lumnik.jdbc.spi;

import java.util.List;

/**
 * A dialect's expression of a lexicographic comparison over the key columns, together with the plan
 * that says which key value each {@code ?} takes.
 *
 * <p>{@code sql} is a complete boolean expression the builder joins to other clauses with a bare
 * {@code AND} (and, for a watermark ceiling, wraps with {@code OR <watermark> IS NULL}): it must be
 * safe there, so an expansion that contains an {@code OR} must be wrapped in its own parentheses.
 * {@code parameterIndexes} is the bind plan: the {@code i}-th {@code ?} of the SQL takes the value of
 * key column {@code parameterIndexes[i]}. PostgreSQL's row-value form {@code ("a", "b") > (?, ?)}
 * answers {@code [0, 1]}; a dialect without tuple comparison expands to
 * {@code (("a" > ?) OR ("a" = ? AND "b" > ?))} — outer parentheses included — and answers
 * {@code [0, 0, 1]}. A wrong plan drops rows with no dead letter, which is why the builder calls
 * {@link #validateFor} on whatever it receives.
 */
public record KeyComparison(String sql, List<Integer> parameterIndexes) {

  /**
   * Refuses a comparison whose plan cannot be honoured: no SQL, no plan, an empty plan over a
   * non-empty key, or an index naming a value that does not exist. It cannot count the {@code ?} of
   * arbitrary SQL, but it can guarantee every index points at a real key column.
   */
  public KeyComparison validateFor(int columnCount) {
    if (sql == null || sql.isBlank()) throw new IllegalStateException("key comparison has no SQL");
    if (hasTopLevelOr(sql)) {
      throw new IllegalStateException("key comparison has an OR outside any parentheses — wrap the whole "
          + "expansion in parentheses, or `WHERE <it> AND <ceiling>` binds the AND to its last disjunct only: " + sql);
    }
    if (parameterIndexes == null) throw new IllegalStateException("key comparison has no bind plan");
    if (columnCount > 0 && parameterIndexes.isEmpty()) {
      throw new IllegalStateException("key comparison over " + columnCount + " columns binds nothing");
    }
    for (Integer ix : parameterIndexes) {
      if (ix == null || ix < 0 || ix >= columnCount) {
        throw new IllegalStateException(
            "bind plan index " + ix + " is outside the " + columnCount + " key columns");
      }
    }
    return this;
  }

  /**
   * Whether the SQL has the keyword {@code OR} at parenthesis depth 0, outside any quoted identifier or
   * literal. A heuristic on purpose small: it targets the one mistake that is silent — an unwrapped
   * expansion — and reads {@code "x OR y"} (a quoted name) or {@code color} (a longer word) as innocent.
   * It refuses unbalanced parentheses or an unterminated quote outright: a stray {@code )} would push
   * the depth below zero and hide a later top-level {@code OR}.
   */
  private static boolean hasTopLevelOr(String s) {
    int depth = 0;
    char quote = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (quote != 0) {
        if (c == quote) quote = 0;   // a doubled quote ("") closes and reopens: the toggle handles it
        continue;
      }
      if (c == '"' || c == '`' || c == '\'') { quote = c; continue; }
      if (c == '(') depth++;
      else if (c == ')') {
        if (--depth < 0) throw unbalanced(s);
      } else if (depth == 0 && (c == 'o' || c == 'O') && i + 1 < s.length()
          && (s.charAt(i + 1) == 'r' || s.charAt(i + 1) == 'R')
          && (i == 0 || !isWordChar(s.charAt(i - 1)))
          && (i + 2 >= s.length() || !isWordChar(s.charAt(i + 2)))) {
        return true;
      }
    }
    if (depth != 0 || quote != 0) throw unbalanced(s);
    return false;
  }

  private static IllegalStateException unbalanced(String sql) {
    return new IllegalStateException("key comparison has unbalanced parentheses or an unterminated quote: " + sql);
  }

  private static boolean isWordChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }
}
