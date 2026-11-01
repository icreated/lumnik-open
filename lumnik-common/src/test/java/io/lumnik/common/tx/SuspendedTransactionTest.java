package io.lumnik.common.tx;

import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

/**
 * quarkus-narayana-jta's {@code suspendingExisting()} resumes only on success or inside a {@code catch (Exception)}:
 * an Error from the work left the caller's transaction suspended, and the caller's own rollback then threw "The
 * transaction is not active!" over the Error (#1660).
 */
class SuspendedTransactionTest {

    private final TransactionManager tm = mock(TransactionManager.class);
    private final Transaction callers = mock(Transaction.class);

    @Test
    void an_error_from_the_work_resumes_the_callers_transaction_and_leaves_as_itself() throws Exception {
        when(tm.suspend()).thenReturn(callers);
        StackOverflowError error = new StackOverflowError("injected");

        assertThatThrownBy(() -> SuspendedTransaction.call(tm, () -> { throw error; })).isSameAs(error);

        InOrder order = inOrder(tm);
        order.verify(tm).suspend();
        order.verify(tm).resume(callers);
    }

    @Test
    void an_exception_from_the_work_resumes_and_leaves_as_itself() throws Exception {
        when(tm.suspend()).thenReturn(callers);
        IllegalStateException refusal = new IllegalStateException("refused");

        assertThatThrownBy(() -> SuspendedTransaction.call(tm, () -> { throw refusal; })).isSameAs(refusal);

        verify(tm).resume(callers);
    }

    @Test
    void the_result_is_returned_and_the_transaction_resumed() throws Exception {
        when(tm.suspend()).thenReturn(callers);

        assertThat(SuspendedTransaction.call(tm, () -> "done")).isEqualTo("done");

        verify(tm).resume(callers);
    }

    /** No ambient transaction: suspend() answers null and there is nothing to resume. */
    @Test
    void with_no_transaction_to_suspend_nothing_is_resumed() throws Exception {
        SuspendedTransaction.run(tm, () -> { });

        verify(tm).suspend();
        verify(tm, never()).resume(any());
    }

    @Test
    void a_transaction_that_cannot_be_suspended_fails_before_the_work_runs() throws Exception {
        SystemException refused = new SystemException("no");
        when(tm.suspend()).thenThrow(refused);
        boolean[] ran = {false};

        assertThatThrownBy(() -> SuspendedTransaction.run(tm, () -> ran[0] = true))
            .isInstanceOf(IllegalStateException.class).hasCause(refused);

        assertThat(ran[0]).isFalse();
    }

    /** The work has finished: the caller must still hear that its own transaction is not back. */
    @Test
    void a_resume_that_fails_after_a_successful_work_is_reported() throws Exception {
        SystemException refused = new SystemException("no");
        when(tm.suspend()).thenReturn(callers);
        doThrow(refused).when(tm).resume(callers);

        assertThatThrownBy(() -> SuspendedTransaction.run(tm, () -> { }))
            .isInstanceOf(IllegalStateException.class).hasCause(refused);
    }

    /** The work's failure is the cause; a resume that fails after it, Error included, rides along as suppressed. */
    @Test
    void a_resume_that_fails_after_a_failed_work_leaves_the_works_failure_first() throws Exception {
        StackOverflowError workFailure = new StackOverflowError("injected");
        NoClassDefFoundError resumeFailure = new NoClassDefFoundError("injected");
        when(tm.suspend()).thenReturn(callers);
        doThrow(resumeFailure).when(tm).resume(callers);

        assertThatThrownBy(() -> SuspendedTransaction.run(tm, () -> { throw workFailure; }))
            .isSameAs(workFailure).hasSuppressedException(resumeFailure);
    }
}
