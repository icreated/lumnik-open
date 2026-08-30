package io.lumnik.hub.testsupport;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.callback.QuarkusTestMethodContext;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.SystemException;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The net's own guard — a net nobody tests is a net nobody has.
 *
 * <p>The behaviour it protects was measured on a throwaway two-method harness before this file
 * existed: a test that begins a transaction and throws made the NEXT test fail with
 * {@code ARJUNA016051: thread is already associated with a transaction!} — two reds for one defect,
 * the second on innocent code. With the net registered, the same harness produced one red (the real
 * cause) and a green neighbour.
 *
 * <p>That harness cannot stay: a permanent test that deliberately leaks would either fail the build
 * or poison its own neighbours. So the arms are driven here directly instead, by handing the net a
 * {@link QuarkusTestMethodContext} built by hand — which is also the only way to exercise the
 * already-failed branch without an actually-failing test.
 */
@QuarkusTest
class DanglingTransactionNetIT {

  @Inject UserTransaction utx;

  private static QuarkusTestMethodContext contextFor(Throwable cause) throws Exception {
    Method any = DanglingTransactionNetIT.class.getDeclaredMethod("aTestThatLeaks");
    return new QuarkusTestMethodContext(null, List.of(), any, cause);
  }

  /** Only ever used as a Method handle for the fabricated contexts above. */
  @SuppressWarnings("unused")
  private void aTestThatLeaks() {}

  @Test
  void aPassingTestThatLeaksIsRolledBackAndNamed() throws Exception {
    utx.begin();

    assertThatThrownBy(() -> new DanglingTransactionNet().afterEach(contextFor(null)))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("aTestThatLeaks")
        .hasMessageContaining("left a transaction open");

    assertThat(utx.getStatus())
        .as("named AND cleaned — the next test must not inherit it")
        .isEqualTo(Status.STATUS_NO_TRANSACTION);
  }

  @Test
  void anAlreadyFailedTestIsRolledBackInSilence() throws Exception {
    utx.begin();

    // The rollback is the whole job here. Stacking a second failure on an already-red test would
    // bury the real cause, which is exactly the noise this net exists to remove.
    assertThatCode(() -> new DanglingTransactionNet()
        .afterEach(contextFor(new IllegalStateException("the real cause"))))
        .doesNotThrowAnyException();

    assertThat(utx.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
  }

  @Test
  void aTestThatLeftNothingOpenIsUntouched() throws Exception {
    assertThat(utx.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);

    assertThatCode(() -> new DanglingTransactionNet().afterEach(contextFor(null)))
        .as("the net must be inert on the overwhelming majority of tests, which leak nothing")
        .doesNotThrowAnyException();
  }

  @Test
  void aFailedRollbackSpeaksEvenWhenTheTestIsAlreadyRed() throws Exception {
    // The silent branch promises a clean thread. When the rollback fails that promise is void:
    // the next test still inherits the transaction, and silence hides the cascade in the one
    // case where nobody is looking for it.
    StubTransaction stub = new StubTransaction(Status.STATUS_ACTIVE, new SystemException("boom"));

    assertThatThrownBy(() -> new DanglingTransactionNet()
        .afterEach(contextFor(new IllegalStateException("the real cause")), stub))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("could not roll back")
        .hasMessageContaining("may still inherit it");

    assertThat(stub.rollbackAttempts).isEqualTo(1);
  }

  @Test
  void anUnreadableStatusStillGetsABestEffortRollback() throws Exception {
    // An unreadable status is not evidence of a clean thread. Returning without trying would
    // leave the next test to discover the truth — but a clean rollback accuses nobody, because
    // there may have been nothing open at all.
    StubTransaction stub = new StubTransaction(null, null);

    assertThatCode(() -> new DanglingTransactionNet().afterEach(contextFor(null), stub))
        .doesNotThrowAnyException();

    assertThat(stub.rollbackAttempts)
        .as("unreadable is not the same as clean — it must still try")
        .isEqualTo(1);
  }

  @Test
  void anUnreadableStatusOverACleanThreadAccusesNobody() throws Exception {
    // The best-effort rollback added for the unreadable-status path creates its own trap: JTA
    // answers "no transaction on this thread" with IllegalStateException, and reading that as a
    // failed cleanup would fail a test that never leaked anything.
    StubTransaction stub = new StubTransaction(null, new IllegalStateException("no transaction"));

    assertThatCode(() -> new DanglingTransactionNet().afterEach(contextFor(null), stub))
        .as("the thread was clean; the status simply could not say so")
        .doesNotThrowAnyException();

    assertThat(stub.rollbackAttempts).isEqualTo(1);
  }

  /** A UserTransaction that fails where a real one almost never does. */
  private static final class StubTransaction implements UserTransaction {
    private final Integer status;              // null → getStatus() throws
    private final Exception rollbackFailure;   // null → rollback() succeeds
    private int rollbackAttempts;

    StubTransaction(Integer status, Exception rollbackFailure) {
      this.status = status;
      this.rollbackFailure = rollbackFailure;
    }

    @Override public int getStatus() throws SystemException {
      if (status == null) throw new SystemException("status unavailable");
      return status;
    }

    @Override public void rollback() throws SystemException {
      rollbackAttempts++;
      if (rollbackFailure instanceof SystemException e) throw e;
      if (rollbackFailure instanceof RuntimeException e) throw e;
    }

    @Override public void begin() { throw new UnsupportedOperationException(); }
    @Override public void commit() { throw new UnsupportedOperationException(); }
    @Override public void setRollbackOnly() { throw new UnsupportedOperationException(); }
    @Override public void setTransactionTimeout(int seconds) { /* unused */ }
  }

  @Test
  void theNetIsRegisteredSoNoTestHasToOptIn() throws Exception {
    // Registration is the whole design: 38 of the 64 classes in this module that begin a
    // transaction have no rollback path, and the point was to fix that without editing 38 files.
    // Delete the service file and every one of them silently goes back to poisoning its neighbour.
    String services;
    try (InputStream in = getClass().getResourceAsStream(
        "/META-INF/services/io.quarkus.test.junit.callback.QuarkusTestAfterEachCallback")) {
      assertThat(in).as("the Quarkus after-each service file must exist").isNotNull();
      services = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertThat(services)
        .as("the service file must name this net, or it is registered nowhere")
        .contains(DanglingTransactionNet.class.getName());
  }
}
