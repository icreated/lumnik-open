package io.lumnik.hub.connector.mapping.writer;

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
 * #1649: a connector's purge deletes every registry row first (DELETE … RETURNING), then drops the tables they name.
 * An Error in between skipped the rollback, and the finally that restored autocommit COMMITTED the registry delete:
 * every table left behind, with no row naming it any more.
 */
class DynamicTableRegistryPurgeRollbackTest {

    @Test
    void an_error_after_the_registry_delete_rolls_it_back() throws Exception {
        AgroalDataSource ds = mock(AgroalDataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement delete = mock(PreparedStatement.class);
        ResultSet deleted = mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.getAutoCommit()).thenReturn(true);   // as Agroal hands it out
        when(conn.prepareStatement(contains("RETURNING"))).thenReturn(delete);
        when(delete.executeQuery()).thenReturn(deleted);
        StackOverflowError error = new StackOverflowError("injected");
        when(deleted.next()).thenThrow(error);          // the rows are deleted; reading back which ones fails
        DynamicTableRegistry registry = new DynamicTableRegistry();
        registry.adminDs = ds;

        assertThatThrownBy(() -> registry.purgeForConnector(UUID.randomUUID())).isSameAs(error);

        var order = inOrder(conn, delete);   // the delete ran inside the transaction that was rolled back
        order.verify(conn).setAutoCommit(false);
        order.verify(delete).executeQuery();
        order.verify(conn).rollback();
        verify(conn, never()).commit();
    }
}
