package io.lumnik.hub.connector.runtime;

import io.lumnik.hub.connector.domain.Connector;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an operator is told a run failed of.
 *
 * <p>The finding: a promoted column typed {@code timestamptz} against a JSON string makes
 * PostgreSQL say exactly the right thing — {@code column "ordered_at" is of type timestamp with
 * time zone but expression is of type character varying}, SQLSTATE 42804, a sentence about
 * structure carrying no row values — and the operator received only
 * {@code HybridJsonbWriter failed for ext.acme_ts_notransform}. The database had written the
 * answer; a wrapper was standing in front of it.
 *
 * <p>The wrapper is not the defect and is not removed: it names the table, which the SQLSTATE
 * message does not. Both travel now.
 */
class ChunkFailureMessageTest {

  private static Connector connector() {
    Connector c = new Connector();
    c.setName("acme-timestamps");
    return c;
  }

  private static String messageFor(Throwable t) {
    return ChunkExecutor.failure(connector(), t).message();
  }

  @Test
  void a_wrapped_structural_sqlstate_reaches_the_operator() {
    SQLException pg = new SQLException(
        "column \"ordered_at\" is of type timestamp with time zone but expression is of type "
        + "character varying\n  Hint: You will need to rewrite or cast the expression.",
        "42804");
    String msg = messageFor(new RuntimeException(
        "HybridJsonbWriter failed for ext.acme_ts_notransform", pg));

    assertThat(msg)
        .as("the wrapper still names the table the SQLSTATE message cannot")
        .contains("ext.acme_ts_notransform")
        .as("and PostgreSQL's own sentence is no longer swallowed")
        .contains("is of type timestamp with time zone but expression is of type character varying");
    assertThat(msg)
        .as("first line only — the Hint is for the server log, not the run row")
        .doesNotContain("Hint:");
  }

  @Test
  void a_data_class_sqlstate_is_withheld_and_says_so() {
    // Class 22 is data_exception: `invalid input syntax for type bigint: "abc"` carries a stored
    // value. Surfacing it would turn a failure message into a channel for row content.
    SQLException pg = new SQLException("invalid input syntax for type bigint: \"abc\"", "22P02");
    String msg = messageFor(new RuntimeException("HybridJsonbWriter failed for ext.orders", pg));

    assertThat(msg).contains("ext.orders").contains("SQLSTATE 22P02");
    assertThat(msg).doesNotContain("abc");
  }

  @Test
  void a_failure_that_already_says_everything_is_not_repeated() {
    // The REST guard's refusal is its own root: nothing to append, and appending would print the
    // same sentence twice on the one line an operator reads.
    String msg = messageFor(new IllegalStateException("upstream returned HTTP 500 (transient)"));
    assertThat(msg).isEqualTo("upstream returned HTTP 500 (transient)");
  }
}
