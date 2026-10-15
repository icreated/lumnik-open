package io.lumnik.hub.run;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.sql.Connection;
import java.sql.SQLException;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #952: a claim holds a {@code FOR UPDATE} row lock for as long as its transaction lives. A session that
 * could not be rolled back and reset may still hold it, and returned to the pool it would hide that row
 * from every dispatcher until the session dies. So, like {@code RunLock#close} for an advisory lock, such a
 * session is destroyed, never pooled.
 */
class RunQueueClaimReleaseTest {

    private final Connection pooled = mock(Connection.class);     // the pool's wrapper
    private final Connection physical = mock(Connection.class);   // the session under it

    @BeforeEach
    void wire() throws SQLException {
        when(pooled.unwrap(Connection.class)).thenReturn(physical);
    }

    @Test
    void an_uncommitted_claim_is_rolled_back_reset_and_returned_to_the_pool() throws Exception {
        RunQueueClaim.release(pooled, false);

        InOrder order = inOrder(pooled);
        order.verify(pooled).rollback();
        order.verify(pooled).setAutoCommit(true);
        order.verify(pooled).close();
        verify(physical, never()).close();
    }

    @Test
    void a_committed_claim_is_reset_without_a_second_rollback() throws Exception {
        RunQueueClaim.release(pooled, true);

        verify(pooled, never()).rollback();
        verify(pooled).setAutoCommit(true);
        verify(pooled).close();
        verify(physical, never()).close();
    }

    @Test
    void a_failed_rollback_destroys_the_physical_session_instead_of_pooling_it() throws Exception {
        doThrow(new SQLException("connection reset")).when(pooled).rollback();

        RunQueueClaim.release(pooled, false);

        verify(physical).close();                      // the session that may still hold the row lock is ended
        verify(pooled).close();                        // and the dead wrapper is still handed back
        verify(pooled, never()).setAutoCommit(anyBoolean());
    }

    @Test
    void a_failed_autocommit_reset_destroys_the_physical_session_too() throws Exception {
        doThrow(new SQLException("no longer valid")).when(pooled).setAutoCommit(true);

        RunQueueClaim.release(pooled, true);

        verify(physical).close();
        verify(pooled).close();
    }
}
