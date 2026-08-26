package io.lumnik.hub.audit;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F3. {@link AuditEntityListener#writeOutboxRow} is the extracted, directly-callable body of the
 * savepoint block {@code insertOutboxRow} runs inside {@code Session.doWork(...)} — pure unit
 * tests here, no Quarkus container and no database, driving it with a mocked JDBC
 * {@link Connection}.
 * <p>
 * Pins two failure shapes the savepoint block did not previously handle:
 * <ol>
 *   <li>the INSERT fails, and the subsequent {@code rollback(savepoint)} ALSO fails — the
 *   original insert failure must not be discarded, and something must still be thrown so the
 *   business transaction is not silently treated as fine;</li>
 *   <li>something other than a {@link SQLException} is thrown between {@code setSavepoint()} and
 *   the INSERT completing — the savepoint must still be rolled back, not left dangling.</li>
 * </ol>
 * A third test pins the already-correct case (plain INSERT failure, rollback succeeds) as a
 * regression guard.
 */
class AuditEntityListenerSavepointTest {

    private final AuditEntityListener listener = new AuditEntityListener();

    @Test
    void insert_failure_rolls_back_to_the_savepoint_and_rethrows_the_original() throws Exception {
        Connection connection = mock(Connection.class);
        Savepoint savepoint = mock(Savepoint.class);
        when(connection.setSavepoint()).thenReturn(savepoint);
        when(connection.unwrap(Connection.class)).thenReturn(connection);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(ps);
        SQLException insertFailure = new SQLException("insert failed");
        when(ps.executeUpdate()).thenThrow(insertFailure);

        SQLException thrown = assertThrows(SQLException.class, () ->
            listener.writeOutboxRow(connection, 1L, 2L, "User", "1", "UPDATE", "{}"));

        assertSame(insertFailure, thrown, "the original insert failure must propagate");
        verify(connection).rollback(savepoint);
    }

    @Test
    void rollback_failure_keeps_the_original_exception_as_suppressed_and_still_throws() throws Exception {
        Connection connection = mock(Connection.class);
        Savepoint savepoint = mock(Savepoint.class);
        when(connection.setSavepoint()).thenReturn(savepoint);
        when(connection.unwrap(Connection.class)).thenReturn(connection);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(ps);
        SQLException insertFailure = new SQLException("insert failed");
        when(ps.executeUpdate()).thenThrow(insertFailure);
        SQLException rollbackFailure = new SQLException("rollback failed too");
        doThrow(rollbackFailure).when(connection).rollback(savepoint);

        SQLException thrown = assertThrows(SQLException.class, () ->
            listener.writeOutboxRow(connection, 1L, 2L, "User", "1", "UPDATE", "{}"));

        assertSame(rollbackFailure, thrown,
            "when the rollback itself fails, that is the exception the caller must see");
        assertTrue(List.of(thrown.getSuppressed()).contains(insertFailure),
            "the original insert failure must be preserved as suppressed, not silently discarded");
    }

    @Test
    void non_sql_exception_between_savepoint_and_insert_still_triggers_a_rollback() throws Exception {
        Connection connection = mock(Connection.class);
        Savepoint savepoint = mock(Savepoint.class);
        when(connection.setSavepoint()).thenReturn(savepoint);
        when(connection.unwrap(Connection.class)).thenReturn(connection);
        RuntimeException boom = new RuntimeException("boom - not a SQLException");
        when(connection.prepareStatement(anyString())).thenThrow(boom);

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
            listener.writeOutboxRow(connection, 1L, 2L, "User", "1", "UPDATE", "{}"));

        assertSame(boom, thrown, "the original non-SQLException failure must propagate");
        verify(connection).rollback(savepoint);
    }

    @Test
    void success_releases_the_savepoint_and_never_rolls_back() throws Exception {
        Connection connection = mock(Connection.class);
        Savepoint savepoint = mock(Savepoint.class);
        when(connection.setSavepoint()).thenReturn(savepoint);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeUpdate()).thenReturn(1);

        listener.writeOutboxRow(connection, 1L, 2L, "User", "1", "UPDATE", "{}");

        verify(connection).releaseSavepoint(savepoint);
    }
}
