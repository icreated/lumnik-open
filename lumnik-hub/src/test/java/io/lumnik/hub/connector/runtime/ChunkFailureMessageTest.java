package io.lumnik.hub.connector.runtime;

import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.exceptions.SourceCallException;
import io.lumnik.hub.connector.exceptions.SourceUnreachableException;
import io.lumnik.hub.connector.mapping.hooks.cell.ScriptFailedException;
import io.lumnik.hub.connector.rest.RestHttpClient;
import io.lumnik.hub.connector.rest.RestRequest;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

  private static final String SOURCE = "https://user:pw@api.example/p/SECRET?k=v";

  private static void assertQuotesNothingOfTheSource(String msg) {
    assertThat(msg).doesNotContain("user", "pw", "ECRET", "k=v");
  }

  /**
   * A call to the source that threw is stored by its kind (#1166). Its cause is the JDK's, and the
   * root-cause rule above would append that cause's message — the very one that quotes the URL.
   */
  @Test
  void a_call_to_the_source_that_threw_is_stored_by_its_kind_alone() {
    String msg = messageFor(new SourceCallException(
        new Exception("Connection refused: " + SOURCE, new ConnectException(SOURCE))));

    assertThat(msg).isEqualTo("connection failed (Exception)");
    assertQuotesNothingOfTheSource(msg);
  }

  /** A source that is gone, said by a chunk: the sentence is the whole account, its host never appended (#1389). */
  @Test
  void a_source_that_is_unreachable_is_stored_as_its_sentence_alone() {
    String sentence = "could not reach public.workorder (SQLSTATE 08001): host not resolved (UnknownHostException)";
    String msg = messageFor(new SourceUnreachableException(sentence,
        new SQLException("The connection attempt failed.", "08001", new UnknownHostException("zombie-cmms-gone"))));

    assertThat(msg).isEqualTo(sentence);
  }

  /**
   * A real JDK refusal, thrown before any I/O: a space in the composed URL makes {@code URI.create}
   * inside the real client refuse it. Its message and its cause's both carried the whole URL; the
   * type stays the one every caller already handles.
   */
  @Test
  void a_url_the_client_cannot_parse_is_stored_without_quoting_it() {
    Exception refused = assertThrows(IllegalArgumentException.class,
        () -> new RestHttpClient().execute(RestRequest.get(SOURCE + " w")));

    String msg = messageFor(refused);

    assertThat(msg).isEqualTo("invalid URL: Illegal character in query at index 40");
    assertQuotesNothingOfTheSource(msg);
  }

  @Test
  void a_failure_that_already_says_everything_is_not_repeated() {
    // The REST guard's refusal is its own root: nothing to append, and appending would print the
    // same sentence twice on the one line an operator reads.
    String msg = messageFor(new IllegalStateException("upstream returned HTTP 500 (transient)"));
    assertThat(msg).isEqualTo("upstream returned HTTP 500 (transient)");
  }

  /**
   * #1434: a failure whose message the hub wrote whole is stored as written — not followed by its cause's, which
   * #1432 keeps out: a Kotlin script's toInt() quotes the cell it could not read.
   */
  @Test
  void an_authored_failure_is_stored_without_its_cause_message() {
    String msg = messageFor(new ScriptFailedException(
        "kotlin hook hooks:quantity.kts failed: NumberFormatException at line 5",
        new NumberFormatException("For input string: \"env. 4 kg\"")));

    assertThat(msg).isEqualTo("kotlin hook hooks:quantity.kts failed: NumberFormatException at line 5");
  }

  /** #1434: wherever it sits in the chain — a wrapper around the hook's failure must not bring the cell back. */
  @Test
  void an_authored_failure_under_a_wrapper_is_still_stored_as_written() {
    String msg = messageFor(new RuntimeException("chunk transform failed", new ScriptFailedException(
        "kotlin hook hooks:quantity.kts failed: NumberFormatException at line 5",
        new NumberFormatException("For input string: \"env. 4 kg\""))));

    assertThat(msg).isEqualTo("kotlin hook hooks:quantity.kts failed: NumberFormatException at line 5");
  }
}
