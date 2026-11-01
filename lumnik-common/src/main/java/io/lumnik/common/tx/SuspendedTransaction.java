package io.lumnik.common.tx;

import jakarta.transaction.InvalidTransactionException;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;

import java.util.function.Supplier;

/**
 * Work run with the caller's transaction suspended, resumed however the work ends.
 *
 * <p>Not {@code QuarkusTransaction.suspendingExisting()}: its resume sits in the success path and in a
 * {@code catch (Exception)} (quarkus-narayana-jta 3.33.3.1), so an Error from the work (an OOM, a linkage error) left
 * the caller's transaction suspended, and the caller's own rollback then threw "The transaction is not active!" over
 * the Error (#1660). Here an exception or an Error from the work leaves as itself; a resume that fails after it is
 * added as suppressed. A transaction that cannot be suspended, or a resume that fails after the work succeeded, is an
 * {@link IllegalStateException}: the caller's transaction is not where it left it.
 */
public final class SuspendedTransaction {

    private SuspendedTransaction() {}

    public static <T> T call(TransactionManager tm, Supplier<T> work) {
        Transaction suspended;
        try {
            suspended = tm.suspend();
        } catch (SystemException e) {
            throw new IllegalStateException("could not suspend the caller's transaction", e);
        }
        T result;
        try {
            result = work.get();
        } catch (Throwable failure) {
            try {
                resume(tm, suspended);
            } catch (Throwable resumeFailure) {   // an Error too: the same pressure that killed the work can kill the resume
                failure.addSuppressed(resumeFailure);
            }
            throw failure;
        }
        resume(tm, suspended);
        return result;
    }

    public static void run(TransactionManager tm, Runnable work) {
        call(tm, () -> {
            work.run();
            return null;
        });
    }

    private static void resume(TransactionManager tm, Transaction suspended) {
        if (suspended == null) return;   // no ambient transaction: nothing was suspended
        try {
            tm.resume(suspended);
        } catch (InvalidTransactionException | SystemException e) {
            throw new IllegalStateException("could not resume the caller's transaction", e);
        }
    }
}
