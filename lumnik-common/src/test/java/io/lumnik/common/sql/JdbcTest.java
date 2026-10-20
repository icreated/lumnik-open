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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
    private final PreparedStatement ps = mock(PreparedStatement.class);
    private final ResultSet rs = mock(ResultSet.class);

    private void wire() throws SQLException {
        when(ds.getConnection()).thenReturn(conn);
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
        when(ds.getConnection()).thenReturn(conn);
        when(conn.getAutoCommit()).thenReturn(true);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
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
        when(conn.getAutoCommit()).thenReturn(true);
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
        when(ds.getConnection()).thenReturn(conn);
        when(conn.getAutoCommit()).thenReturn(true);

        Jdbc.transaction(ds, "T.tx", c -> { });

        var order = inOrder(conn);
        order.verify(conn).setAutoCommit(false);
        order.verify(conn).commit();
        order.verify(conn).setAutoCommit(true);
        order.verify(conn).close();
    }

    @Test
    void transaction_rolls_back_restores_autocommit_and_wraps_the_failure() throws Exception {
        when(ds.getConnection()).thenReturn(conn);
        when(conn.getAutoCommit()).thenReturn(true);
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
        when(ds.getConnection()).thenReturn(conn);
        when(conn.getAutoCommit()).thenReturn(true);
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
        when(ds.getConnection()).thenReturn(conn);
        when(conn.getAutoCommit()).thenReturn(true);
        SQLException bodyFailure = new SQLException("second write failed", "40001");
        SQLException restoreFailure = new SQLException("connection reset");
        doThrow(restoreFailure).when(conn).setAutoCommit(true);

        assertThatThrownBy(() -> Jdbc.transaction(ds, "T.tx", (Jdbc.TxBody) c -> { throw bodyFailure; }))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("T.tx")
            .cause()
            .isSameAs(bodyFailure)
            .satisfies(cause -> assertThat(cause.getSuppressed()).containsExactly(restoreFailure));
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
}
