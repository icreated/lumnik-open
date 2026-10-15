package io.lumnik.hub.run;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RunLock#runIfAbandoned} (#711): the shared "acquire this key's lock, run the domain
 * action only if nobody live holds it" shape every {@code *Reaper} used to repeat inline.
 */
@QuarkusTest
class RunLockIT {

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @Test
    void skipsAndNeverRunsTheActionWhenAnotherSessionHoldsTheLock() throws Exception {
        UUID key = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();

        try (RunLock live = RunLock.tryAcquire(adminDs, key)) {
            assertThat(live).as("the test must hold the lock").isNotNull();

            boolean reaped = RunLock.runIfAbandoned(adminDs, key, () -> {
                calls.incrementAndGet();
                return true;
            });

            assertThat(reaped).as("a live owner elsewhere means this key is never abandoned").isFalse();
            assertThat(calls).as("the domain action must never run while the lock is held").hasValue(0);
        }
    }

    @Test
    void runsTheActionAndReturnsItsResultWhenTheLockIsFree() throws Exception {
        UUID key = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();

        boolean reaped = RunLock.runIfAbandoned(adminDs, key, () -> {
            calls.incrementAndGet();
            return true;
        });

        assertThat(reaped).as("a free lock means the key is abandoned; the action's own result is returned").isTrue();
        assertThat(calls).as("the domain action must run exactly once").hasValue(1);
    }

    @Test
    void releasesTheLockAfterRunningTheAction() throws Exception {
        UUID key = UUID.randomUUID();

        RunLock.runIfAbandoned(adminDs, key, () -> true);

        try (RunLock reacquired = RunLock.tryAcquire(adminDs, key)) {
            assertThat(reacquired).as("runIfAbandoned must release the lock once its action returns").isNotNull();
        }
    }
}
