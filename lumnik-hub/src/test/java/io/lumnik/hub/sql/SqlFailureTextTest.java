package io.lumnik.hub.sql;

import io.lumnik.hub.connector.exceptions.ConnectorException;
import org.junit.jupiter.api.Test;

import java.sql.BatchUpdateException;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1648: {@link SqlFailureText#withCause} is what a run stores as its served {@code error_message}. A
 * wrapper that pasted the driver's text into its own message used to reach it verbatim — the root was
 * withheld, the copy in the wrapper was not. The sink now cuts the copy, whatever catch wrote it.
 */
class SqlFailureTextTest {

    private static final String VALUE = "4111-1111";

    /** The shape pgjdbc builds for a failed batch (BatchResultHandler): the INSERT with its bound values
     *  in the batch's message, the server error as its cause — and DynamicTableWriter.insertRows pasted it. */
    private static ConnectorException insertRowsFailure() {
        SQLException server = new SQLException(
            "ERROR: invalid input syntax for type bigint: \"" + VALUE + "\"\n  Position: 52", "22P02");
        BatchUpdateException batch = new BatchUpdateException(
            "Batch entry 0 INSERT INTO connector.t_orders (id, card) VALUES ('1', '" + VALUE + "') was aborted: "
                + server.getMessage() + "  Call getNextException to see other errors in the batch.",
            "22P02", 0, new long[0], server);
        return new ConnectorException("DynamicTableWriter.insertRows failed: " + batch.getMessage(), batch);
    }

    @Test
    void a_wrapper_that_pasted_the_drivers_text_reaches_the_run_without_it() {
        String served = SqlFailureText.withCause(insertRowsFailure());

        assertThat(served).doesNotContain(VALUE).doesNotContain("INSERT INTO")
            .startsWith("DynamicTableWriter.insertRows failed — SQLSTATE 22P02");
    }

    @Test
    void a_structural_sentence_still_reaches_the_run_once() {
        SQLException server = new SQLException("ERROR: column \"card\" does not exist", "42703");
        var failure = new ConnectorException("DynamicTableWriter.ddl: " + server.getMessage(), server);

        assertThat(SqlFailureText.withCause(failure))
            .isEqualTo("DynamicTableWriter.ddl — ERROR: column \"card\" does not exist");
    }

    /** A provider refusing a column type (JdbcDialect.readColumn: "throw to refuse") names the column and the
     *  type — class 0A, feature not supported, is structural: its words reach the run (Oracle's BLOB refusal). */
    @Test
    void a_providers_refusal_of_a_type_keeps_its_words() {
        var refused = new java.sql.SQLFeatureNotSupportedException("column BODY has unsupported type BLOB");
        var failure = new RuntimeException("JDBC read failed on LUMNIK.T: " + refused.getMessage(), refused);

        assertThat(SqlFailureText.withCause(failure))
            .isEqualTo("JDBC read failed on LUMNIK.T — column BODY has unsupported type BLOB");
    }

    /** The SQLSTATE is said once: a wrapper that already names it gets no withheld-root sentence echoing it. */
    @Test
    void a_wrapper_that_did_not_paste_is_left_as_written_and_its_sqlstate_said_once() {
        SQLException server = new SQLException("ERROR: duplicate key value", "23505");
        var failure = new ConnectorException("Writer.op failed (SQLSTATE 23505)", server);

        assertThat(SqlFailureText.withCause(failure)).isEqualTo("Writer.op failed (SQLSTATE 23505)");
    }

    /**
     * What the database said stops at the database: a foreign cause beneath a SQLException (the driver's
     * socket, quoting host:port) is not served — neither by withCause, which ChunkExecutor and export
     * rendering call, nor by rootMessage, which Ask and the workflow diagnostics read (review of #1654).
     */
    @Test
    void a_foreign_cause_beneath_a_sqlexception_is_never_served() {
        SQLException io = new SQLException("An I/O error occurred", "08006",
            new java.net.SocketException("Connection reset by db-7.internal:5432"));
        var failure = new IllegalStateException("Store.op: " + io.getMessage(), io);

        assertThat(SqlFailureText.withCause(failure)).doesNotContain("db-7.internal")
            .startsWith("Store.op — SQLSTATE 08006");
        assertThat(SqlFailureText.rootMessage(failure)).doesNotContain("db-7.internal").startsWith("SQLSTATE 08006");
        assertThat(SqlFailureText.rootMessage(io)).startsWith("SQLSTATE 08006");
    }

    /** Without a SQLException in the chain, the root is still the root: the hub's own words reach the run. */
    @Test
    void a_chain_without_a_sqlexception_still_reads_its_root() {
        var failure = new RuntimeException("Writer.op failed", new IllegalStateException("table t is locked by run 7"));

        assertThat(SqlFailureText.withCause(failure)).isEqualTo("Writer.op failed — table t is locked by run 7");
    }

    /** No SQLSTATE is said once too: failureText's "(no SQLSTATE)" needs no "SQLSTATE unknown" after it (Copilot on #1654). */
    @Test
    void a_failure_with_no_sqlstate_says_so_once() {
        SQLException server = new SQLException("connection reset");
        var failure = new ConnectorException("Writer.op failed (no SQLSTATE)", server);

        assertThat(SqlFailureText.withCause(failure)).isEqualTo("Writer.op failed (no SQLSTATE)");
    }

    /** {@code "op: " + e} pastes toString(): the driver's class goes with its words. */
    @Test
    void a_pasted_tostring_leaves_neither_the_drivers_class_nor_its_words() {
        SQLException server = new SQLException("ERROR: invalid input syntax for type bigint: \"" + VALUE + "\"", "22P02");
        var failure = new IllegalStateException("Store.op: " + server, server);

        assertThat(SqlFailureText.ownMessage(failure)).startsWith("Store.op — SQLSTATE 22P02").doesNotContain("SQLException");
    }

    /** The cut stands in for the SQLException it cut — not for whatever sits at the root beneath it. */
    @Test
    void the_cut_says_the_sqlexception_it_cut_not_a_foreign_root() {
        SQLException io = new SQLException("An I/O error occurred while sending to the backend.", "08006",
            new java.net.SocketException("Connection reset by db-7.internal:5432"));
        var failure = new IllegalStateException("Store.op: " + io.getMessage(), io);

        assertThat(SqlFailureText.ownMessage(failure)).startsWith("Store.op — SQLSTATE 08006")
            .doesNotContain("db-7.internal");
    }
}
