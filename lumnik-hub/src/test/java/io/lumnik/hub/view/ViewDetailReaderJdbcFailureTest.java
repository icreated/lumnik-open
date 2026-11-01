package io.lumnik.hub.view;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #1649 (#1648): a failed ledger probe says its own label and the SQLSTATE, as every Jdbc statement does; the
 * driver's words stay in the cause.
 */
class ViewDetailReaderJdbcFailureTest {

    @Test
    void a_failed_ledger_probe_says_its_label_and_the_sqlstate_only() throws Exception {
        SQLException driver = new SQLException("ERROR: permission denied for view columns", "42501");
        Connection c = mock(Connection.class);
        when(c.prepareStatement(anyString())).thenThrow(driver);

        assertThatThrownBy(() -> ViewDetailReader.hasConflictsColumn(c, "connector.t_orders"))
            .hasMessage("ViewDetailReader.hasConflictsColumn failed (SQLSTATE 42501)")
            .hasCause(driver);
    }
}
