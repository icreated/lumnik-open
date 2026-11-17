package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.SqlLexer;

import java.util.List;
import java.util.Map;

/** The honest answer returned when a query yields 0 rows — a 0-row result is NEVER phrased as a
 *  confident negative (the cardinal sin of enterprise RAG). Held here so the Fidelity OTK can assert
 *  against the exact same text. */
public final class ZeroRowGuard {

  private ZeroRowGuard() {}

  /** The English sentence; the one served follows the request's {@code lang} ({@link AskSentences#zeroRows}). */
  public static final String CAVEAT = AskSentences.zeroRows(null);

  /**
   * #1982: the zero-row result of an ungrouped aggregate — {@code COUNT(*)} always returns a row. True when the one
   * row read nothing: every count is 0 and every other aggregate is {@code NULL}. A total or a minimum of 0 is a
   * figure, not this case. A {@code MAX} over rows whose column is all {@code NULL} is caveated too: the guard cannot
   * tell it from no row. Any other shape fails open and keeps the old answer ({@code ZeroRowGuardTest.notRead}).
   */
  static boolean foundNothing(String sql, List<Map<String, Object>> rows) {
    if (sql == null || rows.size() != 1) return false;
    List<String> aggregates = DisputedRows.ungroupedAggregates(SqlLexer.tokenize(sql));
    if (aggregates.isEmpty() || aggregates.size() != rows.get(0).size()) return false;
    int i = 0;
    for (Object value : rows.get(0).values()) {
      boolean empty = aggregates.get(i++).equals("count")
          ? value instanceof Number n && n.longValue() == 0
          : value == null;
      if (!empty) return false;
    }
    return true;
  }
}
