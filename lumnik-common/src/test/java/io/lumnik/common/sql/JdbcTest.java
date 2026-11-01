package io.lumnik.common.sql;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchRuntimeException;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The helper owns exactly the acquire-bind-execute-map-close ceremony; these tests pin the
 * three behaviors a caller relies on without reading the implementation: resources close in
 * order, the label names the failing operation, and queryOne means "first row or empty" —
 * never an exception for zero rows.
 */
class JdbcTest {

    private final DataSource ds = mock(DataSource.class);
    private final Connection conn = mock(Connection.class);
    private final Connection physical = mock(Connection.class);   // the session under the pool's wrapper
    private final PreparedStatement ps = mock(PreparedStatement.class);
    private final ResultSet rs = mock(ResultSet.class);

    private void wire() throws SQLException {
        when(ds.getConnection()).thenReturn(conn);
        when(conn.getAutoCommit()).thenReturn(true);   // as Agroal hands it out: Jdbc.transaction refuses anything else
        when(conn.unwrap(Connection.class)).thenReturn(physical);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
    }

    @Test
    void update_binds_executes_and_closes_statement_then_connection() throws Exception {
        wire();
        when(ps.executeUpdate()).thenReturn(3);

        int n = Jdbc.update(ds, "T.op", "UPDATE t SET x = ?", b -> b.setLong(1, 7L));

        assertThat(n).isEqualTo(3);
        var order = inOrder(ps, conn);
        order.verify(ps).setLong(1, 7L);
        order.verify(ps).executeUpdate();
        order.verify(ps).close();
        order.verify(conn).close();
    }

    /** #1300: on a connection the caller holds, the helper closes only what it opened — the statement —
     *  and leaves the connection (a JTA one, a transaction body's) open for the caller's next statement. */
    @Test
    void on_an_open_connection_the_statement_closes_and_the_connection_stays_open() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeUpdate()).thenReturn(2);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false, true, false);
        when(rs.getString(1)).thenReturn("v1");

        assertThat(Jdbc.update(conn, "T.op", "UPDATE t SET x = ?", b -> b.setLong(1, 7L))).isEqualTo(2);
        assertThat(Jdbc.query(conn, "T.list", "SELECT v FROM t", b -> {}, r -> r.getString(1))).containsExactly("v1");
        assertThat(Jdbc.queryOne(conn, "T.find", "SELECT v FROM t", b -> {}, r -> r.getString(1))).contains("v1");

        verify(ps).setLong(1, 7L);
        verify(ps, times(3)).close();
        verify(rs, times(2)).close();
        verify(conn, never()).close();
    }

    /** #1300: a statement that fails inside a transaction body is named by its own label — the
     *  transaction rolls back and lets that failure through instead of wrapping it a second time. */
    @Test
    void a_statement_failing_in_a_transaction_keeps_its_own_label_and_cause() throws Exception {
        wire();
        SQLException boom = new SQLException("violates foreign key constraint", "23503");
        when(ps.executeUpdate()).thenThrow(boom);

        assertThatThrownBy(() -> Jdbc.transaction(ds, "T.tx", (Jdbc.TxBody) c -> Jdbc.update(c, "T.insert", "INSERT ...", b -> {})))
            .hasMessage("T.insert failed (SQLSTATE 23503)")
            .cause().isSameAs(boom);
        verify(conn).rollback();
    }

    /** #1300: "is there a row" — the SELECT 1 shape five stores spelled as queryOne(…, rs -> true).isPresent(). */
    @Test
    void exists_answers_whether_the_query_found_a_row() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);

        assertThat(Jdbc.exists(conn, "T.there", "SELECT 1 FROM t WHERE k = ?", b -> b.setLong(1, 7L))).isTrue();
        assertThat(Jdbc.exists(conn, "T.there", "SELECT 1 FROM t WHERE k = ?", b -> b.setLong(1, 8L))).isFalse();
        verify(conn, never()).close();
    }

    /** The same failure rule as the DataSource forms (#1299): label and SQLSTATE, the cause attached. */
    @Test
    void on_an_open_connection_a_sql_failure_carries_the_label() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        SQLException boom = new SQLException("Key (email)=(a@example.org) already exists", "23505");
        when(ps.executeUpdate()).thenThrow(boom);

        assertThatThrownBy(() -> Jdbc.update(conn, "T.op", "INSERT ...", b -> {}))
            .hasMessage("T.op failed (SQLSTATE 23505)")
            .cause().isSameAs(boom);
        verify(conn, never()).close();
    }

    @Test
    void query_maps_every_row_in_order() throws Exception {
        wire();
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getString(1)).thenReturn("a", "b");

        List<String> out = Jdbc.query(ds, "T.list", "SELECT name FROM t WHERE k = ?",
            b -> b.setLong(1, 7L), r -> r.getString(1));

        assertThat(out).containsExactly("a", "b");
    }

    @Test
    void queryOne_returns_first_row_or_empty_never_throws_on_zero_rows() throws Exception {
        wire();
        when(rs.next()).thenReturn(false);
        Optional<String> none = Jdbc.queryOne(ds, "T.find", "SELECT v FROM t WHERE k = ?",
            b -> b.setLong(1, 7L), r -> r.getString(1));
        assertThat(none).isEmpty();

        when(rs.next()).thenReturn(true);
        when(rs.getString(1)).thenReturn("v1");
        Optional<String> one = Jdbc.queryOne(ds, "T.find", "SELECT v FROM t WHERE k = ?",
            b -> b.setLong(1, 7L), r -> r.getString(1));
        assertThat(one).contains("v1");
    }

    /** #1299: the wrapper names the operation and the SQLSTATE, never the driver's text; the cause stays attached. */
    @Test
    void a_sql_failure_carries_the_label_and_the_sqlstate_never_the_driver_text() throws Exception {
        wire();
        SQLException boom = new SQLException(
            "ERROR: duplicate key value violates unique constraint\n  Detail: Key (email)=(a@example.org) already exists.",
            "23505");
        when(ps.executeUpdate()).thenThrow(boom);

        assertThatThrownBy(() -> Jdbc.update(ds, "WorkflowManifestStore.upsert",
                "INSERT ...", b -> {}))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("WorkflowManifestStore.upsert failed (SQLSTATE 23505)")
            .cause().isSameAs(boom);
    }

    /** A runtime failure that carries a SQL cause is still a database failure: same wrapper, the
     *  SQLSTATE read from the chain, the inner message (which may quote the driver) not repeated. */
    @Test
    void a_runtime_failure_with_a_sql_cause_is_wrapped_like_a_sql_failure() throws Exception {
        wire();
        RuntimeException inner = new RuntimeException("Other.op: Key (email)=(a@example.org)",
            new SQLException("Key (email)=(a@example.org)", "23505"));

        assertThatThrownBy(() -> Jdbc.update(ds, "T.op", "UPDATE ...", b -> { throw inner; }))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("T.op failed (SQLSTATE 23505)")
            .cause().isSameAs(inner);
    }

    /** A checked failure that is not SQL names its class only — its message can quote input too. */
    @Test
    void a_checked_non_sql_failure_carries_the_label_and_its_class() throws Exception {
        wire();
        when(rs.next()).thenReturn(true);

        assertThatThrownBy(() -> Jdbc.query(ds, "T.read", "SELECT ...", b -> {},
                r -> { throw new IOException("unexpected token 'a@example.org'"); }))
            .hasMessage("T.read failed (IOException)");
    }

    /** #1299: a typed refusal thrown inside a binder, a mapper or a transaction body keeps its type —
     *  a 400/403/409 must not become a plain RuntimeException, i.e. a 500. The transaction still rolls back. */
    @Test
    void a_typed_refusal_thrown_inside_a_lambda_keeps_its_type() throws Exception {
        wire();
        when(rs.next()).thenReturn(true);
        Refusal refusal = new Refusal();

        assertThatThrownBy(() -> Jdbc.update(ds, "T.op", "UPDATE ...", b -> { throw refusal; }))
            .isSameAs(refusal);
        assertThatThrownBy(() -> Jdbc.queryOne(ds, "T.find", "SELECT ...", b -> {}, r -> { throw refusal; }))
            .isSameAs(refusal);
        assertThatThrownBy(() -> Jdbc.transaction(ds, "T.tx", (Jdbc.TxBody) c -> { throw refusal; }))
            .isSameAs(refusal);
        verify(conn).rollback();
        verify(conn, never()).commit();
    }

    /** Stands in for a typed refusal (BadRequestException, ConfigValidationException…) — this module has no JAX-RS. */
    private static final class Refusal extends RuntimeException {}

    /** #1299: a mapper may answer null; queryOne reads that as "no value", not as an NPE. */
    @Test
    void queryOne_reads_a_null_from_the_mapper_as_empty() throws Exception {
        wire();
        when(rs.next()).thenReturn(true);

        assertThat(Jdbc.queryOne(ds, "T.find", "SELECT ...", b -> {}, r -> null)).isEmpty();
    }

    @Test
    void transaction_commits_and_restores_autocommit_on_success() throws Exception {
        wire();

        Jdbc.transaction(ds, "T.tx", c -> { });

        var order = inOrder(conn);
        order.verify(conn).setAutoCommit(false);
        order.verify(conn).commit();
        order.verify(conn).setAutoCommit(true);
        order.verify(conn).close();
    }

    @Test
    void transaction_rolls_back_restores_autocommit_and_wraps_the_failure() throws Exception {
        wire();
        SQLException boom = new SQLException("second write failed", "40001");

        assertThatThrownBy(() -> Jdbc.transaction(ds, "T.tx", (Jdbc.TxBody) c -> { throw boom; }))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("T.tx failed (SQLSTATE 40001)")
            .cause().isSameAs(boom);

        var order = inOrder(conn);
        order.verify(conn).setAutoCommit(false);
        order.verify(conn).rollback();
        order.verify(conn).setAutoCommit(true);
        order.verify(conn).close();
        verify(conn, never()).commit();
    }

    /** Copilot review, #671: a rollback that itself fails must never replace the original failure
     *  that triggered it — the caller needs to see WHY the transaction failed, not that cleanup
     *  afterward also failed. The cleanup failure is still visible, as a suppressed exception. */
    @Test
    void transaction_whenRollbackAlsoFails_theOriginalFailureSurvivesAsTheCause() throws Exception {
        wire();
        SQLException bodyFailure = new SQLException("second write failed", "40001");
        SQLException rollbackFailure = new SQLException("connection reset");
        doThrow(rollbackFailure).when(conn).rollback();

        assertThatThrownBy(() -> Jdbc.transaction(ds, "T.tx", (Jdbc.TxBody) c -> { throw bodyFailure; }))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("T.tx")
            .cause()
            .isSameAs(bodyFailure)
            .satisfies(cause -> assertThat(cause.getSuppressed()).containsExactly(rollbackFailure));
    }

    /** Same principle for the autocommit-restore step: a restore failure after a rollback must not
     *  eclipse the failure the rollback was already responding to. */
    @Test
    void transaction_whenAutoCommitRestoreAlsoFailsAfterRollback_theOriginalFailureSurvivesAsTheCause()
            throws Exception {
        wire();
        SQLException bodyFailure = new SQLException("second write failed", "40001");
        SQLException restoreFailure = new SQLException("connection reset");
        doThrow(restoreFailure).when(conn).setAutoCommit(true);

        assertThatThrownBy(() -> Jdbc.transaction(ds, "T.tx", (Jdbc.TxBody) c -> { throw bodyFailure; }))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("T.tx")
            .cause()
            .isSameAs(bodyFailure)
            .satisfies(cause -> assertThat(cause.getSuppressed()).containsExactly(restoreFailure));
        verify(physical).close();   // #1649: a connection that would not go back to autocommit is not pooled either
    }

    /**
     * #1649: an Error (an OOM halfway through a body) is rolled back too. Skipped, the connection went back to the pool
     * out of autocommit, and Agroal's reset ({@code setAutoCommit(true)}) made pgjdbc COMMIT the half-done work.
     * The Error leaves as itself, never wrapped.
     */
    @Test
    void transaction_rolls_back_an_error_and_lets_it_through_unwrapped() throws Exception {
        wire();
        OutOfMemoryError oom = new OutOfMemoryError("Java heap space");

        assertThatThrownBy(() -> Jdbc.transaction(ds, "T.tx", (Jdbc.TxBody) c -> { throw oom; })).isSameAs(oom);

        var order = inOrder(conn);
        order.verify(conn).setAutoCommit(false);
        order.verify(conn).rollback();
        order.verify(conn).setAutoCommit(true);
        order.verify(conn).close();
        verify(conn, never()).commit();
    }

    /**
     * #1649: a rollback that fails leaves the transaction open — healthy after a Java failure, and the next
     * {@code setAutoCommit(true)}, the helper's restore or the pool's reset, would make pgjdbc COMMIT it. The session is
     * ended instead, so the server rolls back what it held; the dead wrapper still goes back to the pool.
     */
    @Test
    void a_failed_rollback_ends_the_session_instead_of_restoring_autocommit() throws Exception {
        wire();
        Refusal refusal = new Refusal();
        SQLException rollbackFailure = new SQLException("rollback lost");
        doThrow(rollbackFailure).when(conn).rollback();

        assertThatThrownBy(() -> Jdbc.transaction(ds, "T.tx", (Jdbc.TxBody) c -> { throw refusal; }))
            .isSameAs(refusal)
            .satisfies(t -> assertThat(t.getSuppressed()).containsExactly(rollbackFailure));

        verify(physical).close();
        verify(conn, never()).setAutoCommit(true);
        verify(conn, never()).commit();
        verify(conn).close();
    }

    /** #1649: an Error from the rollback leaves as itself — never suppressed under the body's failure, where a sweep
     *  catching RuntimeException would carry on past it. The session is ended all the same. */
    @Test
    void an_error_from_the_rollback_leaves_as_itself() throws Exception {
        wire();
        OutOfMemoryError oom = new OutOfMemoryError("Java heap space");
        doThrow(oom).when(conn).rollback();

        assertThatThrownBy(() -> Jdbc.transaction(ds, "T.tx", (Jdbc.TxBody) c -> {
            throw new SQLException("second write failed", "40001");
        })).isSameAs(oom);

        verify(physical).close();
        verify(conn, never()).setAutoCommit(true);
    }

    /** #1649: past its first few, the VM throws one shared OutOfMemoryError instance — the body's, then the rollback's.
     *  It leaves as itself, not as addSuppressed's "Self-suppression not permitted". */
    @Test
    void the_same_error_thrown_again_by_the_rollback_leaves_as_itself() throws Exception {
        wire();
        OutOfMemoryError oom = new OutOfMemoryError("Java heap space");
        doThrow(oom).when(conn).rollback();

        assertThatThrownBy(() -> Jdbc.transaction(ds, "T.tx", (Jdbc.TxBody) c -> { throw oom; })).isSameAs(oom);

        verify(physical).close();
    }

    /** Copilot on #1657: a stub or a driver throwing the body's own exception again — from the rollback, then from the
     *  session's close — leaves the failure as it was, not addSuppressed's "Self-suppression not permitted". */
    @Test
    void the_same_exception_thrown_again_by_the_cleanup_leaves_as_the_failure() throws Exception {
        wire();
        SQLException boom = new SQLException("second write failed", "40001");
        doThrow(boom).when(conn).rollback();
        doThrow(boom).when(physical).close();

        assertThatThrownBy(() -> Jdbc.transaction(ds, "T.tx", (Jdbc.TxBody) c -> { throw boom; }))
            .hasMessage("T.tx failed (SQLSTATE 40001)")
            .cause().isSameAs(boom);
    }

    /** A pool failing unchecked — an interceptor's RuntimeException over its SQLException — is named like any database
     *  failure: which transaction, and the SQLSTATE. */
    @Test
    void transaction_names_a_pool_failure_thrown_unchecked() throws Exception {
        SQLException refused = new SQLException("connection refused", "08006");
        when(ds.getConnection()).thenThrow(new RuntimeException("Tenant GUC setup failed", refused));

        assertThatThrownBy(() -> Jdbc.transaction(ds, "T.tx", c -> null))
            .hasMessage("T.tx failed (SQLSTATE 08006)");
    }

    /** #1649: a pool handing out a connection already in a transaction — a JTA one, inside @Transactional on
     *  {@code default} — gets it back untouched: refused and closed, nothing committed or rolled back. */
    @Test
    void transaction_on_a_pooled_connection_already_in_a_transaction_refuses_it_and_closes_it() throws Exception {
        when(ds.getConnection()).thenReturn(conn);
        when(conn.getAutoCommit()).thenReturn(false);

        assertThatThrownBy(() -> Jdbc.transaction(ds, "T.tx", c -> null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("T.tx");

        var order = inOrder(conn);
        order.verify(conn).getAutoCommit();
        order.verify(conn).close();
        verifyNoMoreInteractions(conn);
    }

    /** #1649: the same transaction on a connection the caller holds — committed, autocommit given back, left open. */
    @Test
    void transaction_on_a_held_connection_commits_and_leaves_it_open() throws Exception {
        when(conn.getAutoCommit()).thenReturn(true);

        Integer answer = Jdbc.transaction(conn, "T.tx", c -> 42);

        assertThat(answer).isEqualTo(42);

        var order = inOrder(conn);
        order.verify(conn).setAutoCommit(false);
        order.verify(conn).commit();
        order.verify(conn).setAutoCommit(true);
        verify(conn, never()).close();
    }

    /**
     * #1649: committed, but the connection cannot go back to autocommit — after a commit pgjdbc sends nothing there, so
     * only a connection closed in between gets here. Thrown, never swallowed: a caller that kept going would run its
     * next statements in a transaction nobody owns (a cursor fetched for a whole sweep). Nothing is rolled back: the
     * work is in.
     */
    @Test
    void transaction_on_a_held_connection_throws_a_failed_reset_after_the_commit() throws Exception {
        when(conn.getAutoCommit()).thenReturn(true);
        SQLException reset = new SQLException("This connection has been closed.", "08003");
        doThrow(reset).when(conn).setAutoCommit(true);

        assertThatThrownBy(() -> Jdbc.transaction(conn, "T.tx", c -> 42))
            .hasMessage("T.tx failed (SQLSTATE 08003)")
            .cause().isSameAs(reset);

        var order = inOrder(conn);
        order.verify(conn).commit();
        order.verify(conn).setAutoCommit(true);
        verify(conn, never()).rollback();
    }

    /** #1649: on a held connection a failure rolls back and gives autocommit back — the connection stays the caller's. */
    @Test
    void transaction_on_a_held_connection_rolls_back_and_leaves_it_open() throws Exception {
        when(conn.getAutoCommit()).thenReturn(true);
        SQLException boom = new SQLException("second write failed", "40001");

        assertThatThrownBy(() -> Jdbc.transaction(conn, "T.tx", c -> { throw boom; }))
            .hasMessage("T.tx failed (SQLSTATE 40001)")
            .cause().isSameAs(boom);

        var order = inOrder(conn);
        order.verify(conn).setAutoCommit(false);
        order.verify(conn).rollback();
        order.verify(conn).setAutoCommit(true);
        verify(conn, never()).commit();
        verify(conn, never()).close();
    }

    /** #1649: a connection already out of autocommit is inside a transaction someone else owns — a JTA one, the
     *  caller's. The helper never commits it, nor rolls it back: refused before it touches anything. */
    @Test
    void transaction_on_a_held_connection_refuses_one_already_in_a_transaction() throws Exception {
        when(conn.getAutoCommit()).thenReturn(false);

        assertThatThrownBy(() -> Jdbc.transaction(conn, "T.tx", c -> null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("T.tx");

        verify(conn, only()).getAutoCommit();
    }

    /**
     * #1648: the same words for a block that cannot use the helpers (a batch, a cursor) and keeps its
     * own exception type — the label and the SQLSTATE, never the driver's text, which quotes values.
     */
    @Test
    void failure_text_says_the_label_and_the_sqlstate_never_the_drivers_words() {
        SQLException boom = new SQLException("ERROR: invalid input syntax for type bigint: \"4111-1111\"", "22P02");
        assertThat(Jdbc.failureText("T.op", boom)).isEqualTo("T.op failed (SQLSTATE 22P02)");
        assertThat(Jdbc.failureText("T.op", new RuntimeException("wrapped", boom)))
            .isEqualTo("T.op failed (SQLSTATE 22P02)");
        assertThat(Jdbc.failureText("T.op", new SQLException("no state"))).isEqualTo("T.op failed (no SQLSTATE)");
    }

    /** A caller that keeps its own type re-labels a helper's failure: a checked failure that is not SQL keeps its class,
     *  not the name of the helper's private wrapper (Copilot on #1666). */
    @Test
    void failure_text_names_what_a_helper_s_failure_wraps() throws Exception {
        wire();
        RuntimeException helper = catchRuntimeException(() ->
            Jdbc.transaction(ds, "T.tx", (Jdbc.TxBody) c -> { throw new IOException("unexpected token 'a@example.org'"); }));

        assertThat(Jdbc.failureText("Caller.op", helper)).isEqualTo("Caller.op failed (IOException)");
    }

    /** #1298: a timestamptz column reads as an Instant, SQL NULL as null — by index and by label —
     *  and the microseconds PostgreSQL keeps survive (Timestamp.getTime() would drop them). */
    @Test
    void instant_reads_a_timestamptz_null_safe_with_its_microseconds() throws Exception {
        Instant at = Instant.parse("2026-10-19T17:37:05.123456Z");
        when(rs.getTimestamp(1)).thenReturn(Timestamp.from(at));
        when(rs.getTimestamp(2)).thenReturn(null);
        when(rs.findColumn("created_at")).thenReturn(1);
        when(rs.findColumn("ended_at")).thenReturn(2);

        assertThat(Jdbc.instant(rs, 1)).isEqualTo(at);
        assertThat(Jdbc.instant(rs, 2)).isNull();
        assertThat(Jdbc.instant(rs, "created_at")).isEqualTo(at);
        assertThat(Jdbc.instant(rs, "ended_at")).isNull();
    }

    /** #1658: the transaction-scoped advisory lock four stores spelled by hand. */
    @Test
    void advisoryXactLock_takes_the_transaction_lock_on_the_hash_of_the_key() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        when(conn.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(mock(ResultSet.class));

        Jdbc.advisoryXactLock(conn, "T.lock", "rag.chunk:1|s");

        verify(ps).setString(1, "rag.chunk:1|s");
        verify(ps).executeQuery();
    }

    @Test
    void a_lock_that_fails_names_its_label_and_sqlstate() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenThrow(new SQLException("deadlock detected", "40P01"));

        assertThatThrownBy(() -> Jdbc.advisoryXactLock(conn, "T.lock", "k"))
            .hasMessageContaining("T.lock").hasMessageContaining("40P01").hasMessageNotContaining("deadlock detected");
    }
}
