package io.lumnik.hub.connector.jdbc;

import io.lumnik.hub.connector.api.types.RunBound;

import java.util.List;

/**
 * The greatest key row the table held when the run started — the watermark plus the primary key in
 * WATERMARK mode, the primary key alone in FULL — so a jdbc run drains what existed and completes
 * instead of following the inserts.
 *
 * <p>Without it {@code JdbcQueryBuilder} pages on {@code (keys) > (last) ORDER BY keys LIMIT n} with
 * no ceiling, so a table receiving rows faster than the hub reads them feeds chunks for as long as
 * the inserts continue: the run ends when the WRITERS pause, not when the reader has caught up. The
 * exact shape kafka had.
 *
 * <p><b>These values never touch jsonb.</b> They are read from the source and bound straight back
 * into the next query, in memory, for the life of one run — which is the whole reason the ceiling
 * lives in the {@code WHERE} clause instead of being compared in the JVM. A cursor value comes back
 * out of a {@code jsonb} column and comparing the two in Java is the serialization boundary
 * {@code ConnectorEngine}'s no-progress net documents at length: a {@code POJONode} is never equal
 * to anything parsed, a {@code LongNode} returns an {@code IntNode} — the trap that read 2 279 500
 * rows off a 700-row table. SQL compares them in the source's own types.
 *
 * @param keyColumns the key layout the ceiling was measured over, in order — the very list
 *                   {@link JdbcQueryBuilder#keyColumns} hands the SELECT, so placeholders and values
 *                   cannot drift apart
 * @param values     one value per key column, as the driver returned it
 */
public record JdbcRunBound(List<String> keyColumns, List<Object> values) implements RunBound {

  public JdbcRunBound(List<String> keyColumns, List<Object> values) {
    if (keyColumns.size() != values.size()) {
      throw new IllegalArgumentException(
          "a ceiling needs one value per key column: " + keyColumns + " vs " + values);
    }
    this.keyColumns = List.copyOf(keyColumns);
    this.values = List.copyOf(values);
  }

  /**
   * The COLUMNS, never their values.
   *
   * <p>This string is logged at INFO at the start of every run and reaches {@code
   * connector_run.error_message} on a partial one. The values are the greatest key row of a source
   * table — for a table keyed on an email, an account name or a national id, printing them would
   * put a customer identifier in a shipped log line, on every run of every jdbc endpoint. The
   * operator needs to know WHAT the run was bounded on; the values are in the source.
   */
  @Override
  public String describe() {
    return "the greatest (" + String.join(", ", keyColumns) + ") the table held at run start";
  }
}
