package io.lumnik.hub.ask;

import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionSynchronizationRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rebuild after a connector delete runs from {@code afterCompletion}, with the committed transaction still
 * associated with the thread: it is suspended around the read of the source (#938), and must be resumed whatever the
 * read throws — an Error included, which {@code QuarkusTransaction.suspendingExisting()} did not resume (#1660).
 */
class SchemaCardRefresherAfterCommitTest {

    private final DiscoveryService discovery = mock(DiscoveryService.class);
    private final TransactionManager tm = mock(TransactionManager.class);
    private final Transaction callers = mock(Transaction.class);
    private final TransactionSynchronizationRegistry registry = mock(TransactionSynchronizationRegistry.class);
    private final SchemaCardRefresher refresher = new SchemaCardRefresher();

    SchemaCardRefresherAfterCommitTest() throws Exception {
        refresher.discovery = discovery;
        refresher.txSync = registry;
        refresher.transactionManager = tm;
        when(tm.suspend()).thenReturn(callers);
    }

    private Synchronization afterCommit() {
        refresher.afterCommit(7L, List.of("ventes"));
        ArgumentCaptor<Synchronization> registered = ArgumentCaptor.forClass(Synchronization.class);
        verify(registry).registerInterposedSynchronization(registered.capture());
        return registered.getValue();
    }

    @Test
    void the_rebuild_runs_with_the_committed_transaction_suspended_then_resumed() throws Exception {
        afterCommit().afterCompletion(Status.STATUS_COMMITTED);

        verify(discovery).rediscover(7L, "ventes");
        verify(tm).resume(callers);
    }

    @Test
    void an_error_from_the_rebuild_still_resumes_the_transaction() throws Exception {
        StackOverflowError error = new StackOverflowError("injected");
        doThrow(error).when(discovery).rediscover(7L, "ventes");
        Synchronization sync = afterCommit();

        assertThatThrownBy(() -> sync.afterCompletion(Status.STATUS_COMMITTED)).isSameAs(error);

        verify(tm).resume(callers);
    }
}
