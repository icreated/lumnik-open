package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #1649 (#1648): a failed due-connector read says its label and the SQLSTATE, as every Jdbc read does; the driver's
 * words stay in the cause.
 */
class ConnectorSchedulerFailureTest {

    @Test
    void a_failed_due_read_says_its_label_and_the_sqlstate_only() throws Exception {
        AgroalDataSource ds = mock(AgroalDataSource.class);
        SQLException driver = new SQLException("FATAL: too many connections for role \"lumnik_admin\"", "53300");
        when(ds.getConnection()).thenThrow(driver);
        ConnectorScheduler scheduler = new ConnectorScheduler();
        scheduler.adminDs = ds;

        assertThatThrownBy(scheduler::listDueConnectors)
            .hasMessage("ConnectorScheduler.listDueConnectors failed (SQLSTATE 53300)")
            .hasCause(driver);
    }
}
