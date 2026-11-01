package io.lumnik.hub.app;

import io.agroal.api.AgroalDataSource;
import io.lumnik.testkit.cdi.SingletonInstance;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #1649: a {@code default: true} apply demotes the current default, then writes its own row. An Error in between
 * skipped the rollback, and the finally that restored autocommit COMMITTED the demote: a deployment with no default
 * app, the floor the apply door exists to keep.
 */
class AppRegistrationStoreRollbackTest {

    @Test
    void an_error_between_the_demote_and_the_write_rolls_the_demote_back() throws Exception {
        AgroalDataSource ds = mock(AgroalDataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement demote = mock(PreparedStatement.class);
        PreparedStatement write = mock(PreparedStatement.class);
        ResultSet demoted = mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.getAutoCommit()).thenReturn(true);   // as Agroal hands it out
        when(conn.prepareStatement(anyString())).thenReturn(demote);
        when(conn.prepareStatement(contains("INSERT INTO connector.app_registration"))).thenReturn(write);
        when(demote.executeQuery()).thenReturn(demoted);
        when(demoted.next()).thenReturn(true);
        when(demoted.getString(1)).thenReturn("old-default");
        StackOverflowError error = new StackOverflowError("injected");
        when(write.executeUpdate()).thenThrow(error);
        AppRegistrationStore store = new AppRegistrationStore();
        store.adminDs = new SingletonInstance<>(ds);
        AppManifest manifest = new AppManifest("orders", "sales", "Orders", "box", null, null, true);

        assertThatThrownBy(() -> store.upsert(manifest, "kind: App", null)).isSameAs(error);

        var order = inOrder(conn, demote, write);   // the demote ran inside the transaction that was rolled back
        order.verify(conn).setAutoCommit(false);
        order.verify(demote).executeQuery();
        order.verify(write).executeUpdate();
        order.verify(conn).rollback();
        verify(conn, never()).commit();
    }
}
