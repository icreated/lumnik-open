package io.lumnik.hub.view;

import io.agroal.api.AgroalDataSource;
import io.lumnik.testkit.cdi.SingletonInstance;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #1649 (#1648): the list's failure pasted the driver's exception after its label. A failed list says its label and
 * the SQLSTATE, as every Jdbc read does; the driver's words stay in the cause.
 */
class SavedViewStoreListFailureTest {

    @Test
    void a_failed_list_says_its_label_and_the_sqlstate_only() throws Exception {
        AgroalDataSource ds = mock(AgroalDataSource.class);
        Connection conn = mock(Connection.class);
        when(ds.getConnection()).thenReturn(conn);
        SQLException driver = new SQLException("ERROR: permission denied for table saved_view", "42501");
        when(conn.prepareStatement(anyString())).thenThrow(driver);
        SavedViewStore store = new SavedViewStore();
        store.adminDs = new SingletonInstance<>(ds);

        assertThatThrownBy(() -> store.list(null))
            .hasMessage("SavedViewStore.list failed (SQLSTATE 42501)")
            .hasCause(driver);
    }
}
