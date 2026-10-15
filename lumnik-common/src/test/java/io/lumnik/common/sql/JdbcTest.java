package io.lumnik.common.sql;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    @Test
    void failures_are_wrapped_with_the_label_and_keep_the_cause() throws Exception {
        wire();
        SQLException boom = new SQLException("column does not exist");
        when(ps.executeUpdate()).thenThrow(boom);

        assertThatThrownBy(() -> Jdbc.update(ds, "WorkflowManifestStore.upsert",
                "INSERT ...", b -> {}))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("WorkflowManifestStore.upsert")
            .hasMessageContaining("column does not exist")
            .cause().isSameAs(boom);
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
        RuntimeException boom = new RuntimeException("second write failed");

        assertThatThrownBy(() -> Jdbc.transaction(ds, "T.tx", (Jdbc.TxBody) c -> { throw boom; }))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("T.tx")
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
        RuntimeException bodyFailure = new RuntimeException("second write failed");
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
        RuntimeException bodyFailure = new RuntimeException("second write failed");
        SQLException restoreFailure = new SQLException("connection reset");
        doThrow(restoreFailure).when(conn).setAutoCommit(true);

        assertThatThrownBy(() -> Jdbc.transaction(ds, "T.tx", (Jdbc.TxBody) c -> { throw bodyFailure; }))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("T.tx")
            .cause()
            .isSameAs(bodyFailure)
            .satisfies(cause -> assertThat(cause.getSuppressed()).containsExactly(restoreFailure));
    }
}
