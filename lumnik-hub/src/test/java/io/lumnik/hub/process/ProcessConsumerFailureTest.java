package io.lumnik.hub.process;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.mapping.writer.DynamicTableRegistry;
import io.lumnik.hub.event.EventOutboxRow;
import io.lumnik.hub.event.RowEventGate;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #1649 (#1648): the row fetch pasted the driver's message after its label. A failed fetch says its label and the
 * SQLSTATE, as every Jdbc read does; the driver's words stay in the cause.
 */
class ProcessConsumerFailureTest {

    @Test
    void a_failed_row_fetch_says_its_label_and_the_sqlstate_only() throws Exception {
        AgroalDataSource ds = mock(AgroalDataSource.class);
        Connection conn = mock(Connection.class);
        when(ds.getConnection()).thenReturn(conn);
        SQLException driver = new SQLException("ERROR: permission denied for table t_orders", "42501");
        when(conn.prepareStatement(anyString())).thenThrow(driver);
        ProcessConsumer consumer = new ProcessConsumer();
        consumer.adminDs = ds;
        consumer.entityTargets = new EntityProcessTargets() {
            @Override public List<Target> observing(long tenantId, String sourceTable) { return List.of(); }
            @Override public List<Target> joining(long tenantId, String lookupTable) { return List.of(); }
        };
        consumer.tables = new DynamicTableRegistry() {
            @Override public String rowHashColumn(Long tenantId, String fqn) { return "_row_hash"; }
        };
        consumer.store = new ProcessManifestStore() {
            @Override public List<StoredProcess> findEnabledFor(long tenantId, String table) {
                return List.of(new StoredProcess("p", null, table, List.of(), "decision.p", "m", List.of(), null, true));
            }
        };
        EventOutboxRow row = EventOutboxRow.ofFull(1L, null, RowEventGate.DATA_ROW_CHANGED, "connector:test",
            "connector.t_orders", "hash", Map.of(), null, Instant.EPOCH);

        assertThatThrownBy(() -> consumer.batchProcessor().accept(row))
            .hasMessage("ProcessConsumer.fetchRow failed (SQLSTATE 42501)")
            .hasCause(driver);
    }
}
