package io.lumnik.hub.testsupport;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.test.junit.callback.QuarkusTestAfterEachCallback;
import io.quarkus.test.junit.callback.QuarkusTestMethodContext;
import jakarta.transaction.Status;
import jakarta.transaction.UserTransaction;

/**
 * Hands each test the thread back without an open transaction.
 *
 * <p>A test that calls {@code utx.begin()} and throws before {@code commit()} leaves the
 * transaction associated with the thread. The next test's {@code begin()} then dies of
 * {@code ARJUNA016051: thread is already associated with a transaction!} — one real failure
 * manufactures a second one in a test that touches none of it, and the second points at innocent
 * code. Measured on a two-method harness: without this net, the leaker failed with its own cause
 * AND the neighbour failed with ARJUNA016051.
 *
 * <p>38 of the 64 test classes in this module that begin a transaction have no rollback path
 * (26 do). Rather than edit the 38 — which protects only today's tests, since the 39th will be
 * written by copying a neighbour, exactly how this defect was born — the net closes the seam once.
 *
 * <p><strong>Why a Quarkus callback and not a JUnit {@code AfterEachCallback}.</strong> The JUnit
 * version was written first and was a perfect phantom: registered, fired on every test, and inert.
 * An autodetected JUnit extension is loaded by the system classloader, where {@code Arc} is not
 * running — it read {@code arc=false}, resolved no {@code UserTransaction}, and returned. Quarkus
 * runs its own callbacks inside the test's classloader, which is the only place the container and
 * the thread's transaction are both visible.
 *
 * <p><strong>What it does with a leak</strong> depends on how the test ended, because the two cases
 * want opposite things:
 * <ul>
 *   <li><em>the test passed</em> — the leak is a defect in the test itself, invisible until the day
 *       it costs someone an evening. It fails, and names the method.</li>
 *   <li><em>the rollback itself failed</em> — it speaks, whatever the test's outcome. Silence
 *       would mean promising a clean thread it has not delivered, in the one case where the
 *       cascade returns and nobody is looking for it.</li>
 *   <li><em>the test already failed</em> and the rollback worked — it stays silent. The rollback is what protects
 *       the next test; a second failure stacked on an already-red test would bury the real cause,
 *       which is the noise this net exists to remove.</li>
 * </ul>
 *
 * <p><strong>Measured limit.</strong> {@code isTestFailed()} is sticky within a class: once one
 * test has failed, every later test in that class reports failed too. The same leaking method was
 * accused in a class where it ran alone and passed silently in a class whose first test had failed.
 * So in an already-red class a passing leaker is cleaned but not named. That degrades the
 * accusation, never the protection, and a class that is already red is one someone is reading.
 */
public class DanglingTransactionNet implements QuarkusTestAfterEachCallback {

  @Override
  public void afterEach(QuarkusTestMethodContext context) {
    ArcContainer container = Arc.container();
    if (container == null || !container.isRunning()) {
      return; // no container: a plain unit test, with no transaction to hand back
    }
    // The handle owns the instance when the bean is @Dependent, so the work happens INSIDE the
    // try — closing first and using the result after would hand this net a destroyed transaction.
    try (InstanceHandle<UserTransaction> handle = container.instance(UserTransaction.class)) {
      if (handle.isAvailable()) {
        afterEach(context, handle.get());
      }
    }
  }

  /** The whole decision, over an injectable transaction so the failure paths can be driven. */
  void afterEach(QuarkusTestMethodContext context, UserTransaction utx) {
    Integer status = readStatus(utx);
    if (status != null && status == Status.STATUS_NO_TRANSACTION) {
      return; // the overwhelming majority of tests: nothing left open
    }

    // Either something is open, or the status could not be read. Both get a best-effort rollback:
    // an unreadable status is not evidence of a clean thread, and refusing to try would leave the
    // next test to discover the truth.
    Exception rollbackFailure = null;
    try {
      utx.rollback();
    } catch (IllegalStateException e) {
      // JTA's only meaning for this: the thread carries no transaction. When the status was
      // unreadable that is the answer the status could not give — the thread was clean all along,
      // and accusing the test of leaking would be a false positive manufactured by the very
      // best-effort attempt that made this path exist.
      if (status == null) {
        return;
      }
      rollbackFailure = e; // the status said a transaction was open; the two disagree, so speak
    } catch (Exception e) {
      rollbackFailure = e;
    }

    if (rollbackFailure != null) {
      // The cleanup itself failed, so the next test may still inherit the transaction. Staying
      // silent here — even on an already-red test — would hide the exact cascade this net exists
      // to stop, and hide it in the one case where nobody is looking.
      throw failure(context, "could not roll back a dangling transaction (status "
          + (status == null ? "unreadable" : status) + "); the next test may still inherit it",
          rollbackFailure);
    }
    if (status == null) {
      return; // unreadable status, clean rollback — nothing certain enough to accuse anyone of
    }
    if (context.getTestStatus().isTestFailed()) {
      return; // already red and now clean: do not bury the real cause
    }
    throw failure(context, "passed but left a transaction open (status " + status
        + "). A test that begins a transaction must roll it back when the body throws, or the next"
        + " test's begin() dies of ARJUNA016051 and the failure lands on innocent code.", null);
  }

  /** The transaction's status, or null when the manager cannot be asked. */
  private static Integer readStatus(UserTransaction utx) {
    try {
      return utx.getStatus();
    } catch (Exception e) {
      return null;
    }
  }

  private static AssertionError failure(QuarkusTestMethodContext context, String what,
                                        Exception cause) {
    AssertionError error = new AssertionError(context.getTestMethod().getName() + " " + what);
    if (cause != null) {
      error.addSuppressed(cause);
    }
    return error;
  }
}
