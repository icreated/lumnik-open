package io.lumnik.hub.search.projection;

import io.agroal.api.AgroalDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #1649 (#1648): a failed projection-state statement says its label and the SQLSTATE, as every Jdbc
 * statement does; the driver's words stay in the cause.
 */
class SearchProjectionStateJdbcFailureTest {

    @Test
    void a_failed_mark_ready_says_its_label_and_the_sqlstate_only() throws Exception {
        SQLException driver = new SQLException("ERROR: canceling statement due to statement timeout", "57014");

        assertThatThrownBy(() -> new SearchProjectionStateStore()
                .markReady(refusing(driver), 1L, "idx", "postgres", "gen-1", 1L, UUID.randomUUID()))
            .hasMessage("SearchProjectionStateStore.markReady failed (SQLSTATE 57014)")
            .hasCause(driver);
    }

    @Test
    void a_failed_withheld_read_says_its_label_and_the_sqlstate_only() throws Exception {
        SQLException driver = new SQLException("ERROR: could not obtain lock on row", "55P03");

        assertThatThrownBy(() -> new SearchProjectionStateStore()
                .releaseIfServingTheDeclaration(refusing(driver), 1L, "idx", null))
            .hasMessage("SearchProjectionStateStore.releaseIfServingTheDeclaration read failed (SQLSTATE 55P03)")
            .hasCause(driver);
    }

    @Test
    void a_failed_cancel_read_says_its_label_and_the_sqlstate_only() throws Exception {
        SQLException driver = new SQLException("ERROR: could not obtain lock on row", "55P03");
        Connection c = refusing(driver);
        when(c.getAutoCommit()).thenReturn(true); // a pooled connection, as Jdbc.transaction finds it
        SearchProjectionStateStore store = new SearchProjectionStateStore();
        store.ds = mock(AgroalDataSource.class);
        when(store.ds.getConnection()).thenReturn(c);

        assertThatThrownBy(() -> store.requestCancel(1L, "idx"))
            .hasMessage("SearchProjectionStateStore.requestCancel read failed (SQLSTATE 55P03)")
            .hasCause(driver);
    }

    private static Connection refusing(SQLException driver) throws SQLException {
        Connection c = mock(Connection.class);
        when(c.prepareStatement(anyString())).thenThrow(driver);
        return c;
    }
}
