package io.lumnik.hub.connector.mapping.writer;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.event.RunTableStats;
import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import io.lumnik.hub.entity.EntityViewCompiler;
import io.lumnik.hub.event.RowEventGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #1649: the writer's two transactions caught {@code Exception} only. An {@link Error} after some statements skipped
 * the rollback, and the work was committed anyway — by the pool's reset for the first write's DDL, by the
 * {@code finally} restoring autocommit for the sub-batches the gated insert had written.
 */
class DynamicTableWriterRollbackTest {

    private static final MappingTarget.Table SPEC =
        new MappingTarget.Table("connector", "t_orders", true, MappingTarget.OnConflict.SKIP, List.of());
    private static final Long TENANT = 7L;

    private final StackOverflowError error = new StackOverflowError("injected");
    private final Connection conn = mock(Connection.class);
    private final Statement ddl = mock(Statement.class);
    private final PreparedStatement ps = mock(PreparedStatement.class);
    private final DynamicTableWriter writer = new DynamicTableWriter();

    @BeforeEach
    void wire() throws Exception {
        AgroalDataSource ds = mock(AgroalDataSource.class);
        when(ds.getConnection()).thenReturn(conn);
        AtomicBoolean autoCommit = new AtomicBoolean(true);   // as Agroal hands it out
        when(conn.getAutoCommit()).thenAnswer(i -> autoCommit.get());
        doAnswer(i -> {
            autoCommit.set(i.getArgument(0));
            return null;
        }).when(conn).setAutoCommit(anyBoolean());
        when(conn.createStatement()).thenReturn(ddl);
        when(conn.prepareStatement(anyString())).thenReturn(ps);

        writer.adminDs = ds;
        writer.inferrer = mock(SchemaInferrer.class);
        writer.registry = mock(DynamicTableRegistry.class);
        writer.stats = mock(RunTableStats.class);
        writer.gate = mock(RowEventGate.class);
        writer.driftDetector = mock(DriftDetector.class);
        writer.keyIndex = mock(SourceKeyIndex.class);
        writer.viewCompiler = mock(EntityViewCompiler.class);
        when(writer.inferrer.infer(any(), any())).thenReturn(schema());
        // A first write. Not the mock's default: Mockito's ReturnsEmptyValues answers a Map with an empty one.
        when(writer.registry.findSchema(any(), any(), any())).thenReturn(null);
    }

    @Test
    void an_error_after_the_first_write_s_ddl_rolls_it_back() throws Exception {
        // The table, its indexes and its RLS are created; reading it back fails.
        when(writer.driftDetector.actualSchema(eq(conn), anyString(), anyString())).thenThrow(error);

        assertThatThrownBy(() -> write(null)).isSameAs(error);

        var order = inOrder(conn, ddl);
        order.verify(conn).setAutoCommit(false);
        order.verify(ddl).execute(contains("CREATE TABLE"));
        assertRolledBack(order);
    }

    @Test
    void an_error_after_a_gated_sub_batch_rolls_it_back() throws Exception {
        when(writer.registry.findSchema(TENANT, "m", null)).thenReturn(schema());
        when(writer.gate.enabled(TENANT, "connector.t_orders")).thenReturn(true);
        ResultSet returning = mock(ResultSet.class);
        when(ps.executeQuery()).thenReturn(returning);
        when(returning.next()).thenThrow(error);   // the sub-batch is inserted; reading it back fails

        assertThatThrownBy(() -> write(null)).isSameAs(error);

        var order = inOrder(conn, ps);
        order.verify(conn).setAutoCommit(false);
        order.verify(ps).executeQuery();
        assertRolledBack(order);
    }

    /** The refusal of a second writer is thrown inside the transaction: it rolls back and stays the one thrown. */
    @Test
    void a_second_writer_s_refusal_rolls_back_and_stays_itself() throws Exception {
        UUID connector = UUID.randomUUID();
        when(writer.registry.otherWriter(conn, TENANT, "connector", "t_orders", connector)).thenReturn("orders-a");

        assertThatThrownBy(() -> write(connector))
            .isExactlyInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("already written by connector 'orders-a'");

        assertRolledBack(inOrder(conn));
    }

    /** A database failure leaves as the writer's ConnectorException, labelled with its SQLSTATE (as SourceKeyIndex). */
    @Test
    void a_database_failure_in_the_first_write_is_a_labelled_connector_exception() throws Exception {
        when(writer.driftDetector.actualSchema(eq(conn), anyString(), anyString()))
            .thenThrow(new SQLException("relation \"t_orders\" does not exist", "42P01"));

        assertThatThrownBy(() -> write(null))
            .isExactlyInstanceOf(ConnectorException.class)
            .hasMessage("DynamicTableWriter.createSchema failed (SQLSTATE 42P01)");
        assertRolledBack(inOrder(conn));
    }

    /** pgjdbc's batch failure quotes the row it could not write (#1648): the ungated insert says its SQLSTATE only. */
    @Test
    void a_failed_ungated_batch_does_not_paste_the_row() throws Exception {
        when(writer.registry.findSchema(TENANT, "m", null)).thenReturn(schema());
        when(ps.executeBatch()).thenThrow(new BatchUpdateException(
            "Batch entry 0 INSERT INTO connector.t_orders VALUES ('secret-value') was aborted", "22P02", new int[0]));

        assertThatThrownBy(() -> write(null))
            .isExactlyInstanceOf(ConnectorException.class)
            .hasMessage("DynamicTableWriter.insertRows failed (SQLSTATE 22P02)");
    }

    /** The statement verified before ran inside the transaction that was rolled back, and nothing was committed. */
    private void assertRolledBack(InOrder order) throws Exception {
        order.verify(conn).rollback();
        verify(conn, never()).commit();
    }

    private void write(UUID connector) {
        writer.write(SPEC, List.of(Map.of("name", "x")), sys(), TENANT, connector);
    }

    private static List<Map<String, Object>> sys() {
        return List.of(Map.of("_mapping", "m", "_row_hash", "h1"));
    }

    private static Map<String, String> schema() {
        return new LinkedHashMap<>(Map.of("name", "text"));
    }
}
