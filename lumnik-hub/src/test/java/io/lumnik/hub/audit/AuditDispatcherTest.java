package io.lumnik.hub.audit;

import io.agroal.api.AgroalDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #1649: a drain copies its outbox rows into the event log and marks them dispatched, in one transaction. An Error
 * between the two batches used to skip any rollback; given back to the pool, the connection was reset with
 * {@code setAutoCommit(true)}, on which pgjdbc COMMITs: the events landed, the rows stayed undispatched, and the next
 * drain copied them a second time.
 */
class AuditDispatcherTest {

    private final AgroalDataSource ds = mock(AgroalDataSource.class);
    private final Connection conn = mock(Connection.class);
    private final PreparedStatement sel = mock(PreparedStatement.class);
    private final ResultSet rs = mock(ResultSet.class);
    private final PreparedStatement ins = mock(PreparedStatement.class);
    private final PreparedStatement upd = mock(PreparedStatement.class);
    private final AuditDispatcher dispatcher = new AuditDispatcher();

    @BeforeEach
    void wire() throws SQLException {
        dispatcher.adminDs = ds;
        when(ds.getConnection()).thenReturn(conn);
        when(conn.getAutoCommit()).thenReturn(true);   // as Agroal hands it out
        when(rs.next()).thenReturn(true, false);       // one pending row
        when(conn.prepareStatement(contains("INSERT"))).thenReturn(ins);
        when(conn.prepareStatement(contains("UPDATE"))).thenReturn(upd);
        when(conn.prepareStatement(contains("SELECT"))).thenReturn(sel);   // last: its FOR UPDATE matches the line above
        when(sel.executeQuery()).thenReturn(rs);
    }

    @Test
    void an_error_between_the_two_batches_rolls_both_back() throws Exception {
        OutOfMemoryError oom = new OutOfMemoryError("Java heap space");
        when(upd.executeBatch()).thenThrow(oom);

        assertThatThrownBy(dispatcher::drain).isSameAs(oom);

        var order = inOrder(conn, ins);   // the events went in inside the transaction that was rolled back
        order.verify(conn).setAutoCommit(false);
        order.verify(ins).executeBatch();
        order.verify(conn).rollback();
        verify(conn, never()).commit();
    }
}
