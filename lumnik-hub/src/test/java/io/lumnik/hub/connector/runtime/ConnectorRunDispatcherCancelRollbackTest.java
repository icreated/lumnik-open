package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #1649: a queued run's cancel ends the run, then stamps its endpoint, in one transaction. An Error in between skipped
 * the rollback, and the pool's reset ({@code setAutoCommit(true)}) COMMITTED the run's ending without the stamp.
 */
class ConnectorRunDispatcherCancelRollbackTest {

    @Test
    void an_error_between_the_run_and_its_endpoint_stamp_rolls_the_run_back() throws Exception {
        AgroalDataSource ds = mock(AgroalDataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement run = mock(PreparedStatement.class);
        PreparedStatement stamp = mock(PreparedStatement.class);
        ResultSet ended = mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.getAutoCommit()).thenReturn(true);   // as Agroal hands it out
        when(conn.prepareStatement(contains("SET LOCAL lock_timeout"))).thenReturn(mock(PreparedStatement.class));
        when(conn.prepareStatement(contains("RETURNING endpoint_id"))).thenReturn(run);
        when(conn.prepareStatement(contains("connector_endpoint SET last_run_status"))).thenReturn(stamp);
        when(run.executeQuery()).thenReturn(ended);
        when(ended.next()).thenReturn(true, false);
        when(ended.getObject(1, UUID.class)).thenReturn(UUID.randomUUID());
        StackOverflowError error = new StackOverflowError("injected");
        when(stamp.executeUpdate()).thenThrow(error);
        ConnectorRunDispatcher dispatcher = new ConnectorRunDispatcher();
        dispatcher.adminDs = ds;

        assertThatThrownBy(() -> dispatcher.cancelQueued(UUID.randomUUID(), 7L)).isSameAs(error);

        var order = inOrder(conn, run, stamp);   // the run's ending ran inside the transaction that was rolled back
        order.verify(conn).setAutoCommit(false);
        order.verify(run).executeQuery();
        order.verify(stamp).executeUpdate();
        order.verify(conn).rollback();
        verify(conn, never()).commit();
    }
}
