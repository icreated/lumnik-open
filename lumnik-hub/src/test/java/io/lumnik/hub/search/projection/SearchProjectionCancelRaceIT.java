package io.lumnik.hub.search.projection;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spec test 10: both directions of the cancel-vs-cutover race, driven through the SAME two real
 * mechanisms that contend for the row lock — requestCancel's SELECT ... FOR UPDATE and runBuild's
 * own casToPublishing CAS — never a bare UPDATE standing in for either.
 *
 * <p>The first two tests below drive requestCancel and casToPublishing sequentially, on one thread,
 * to pin each direction's outcome exactly. The third proves requestCancel genuinely blocks on a row
 * a concurrent transaction holds via FOR UPDATE. The fourth ({@link
 * #concurrentCancelAndCasNeverBothWinTheSameRun}) is the test that actually exercises two real
 * threads racing THESE TWO MECHANISMS against each other, repeatedly, and is the one that would
 * catch a regression a sequential test cannot: swapping requestCancel's {@code SELECT ... FOR
 * UPDATE} for a bare {@code SELECT} still passes the third test (its trailing, unconditional {@code
 * UPDATE cancel_requested_run_id = ...} still blocks on the held row lock regardless — the block
 * that test proves is real, just not sourced from the read), but reliably fails THIS test within a
 * handful of the 20 repetitions: a bare-SELECT requestCancel can read the stale 'building' phase
 * before casToPublishing's own commit, then still blindly write cancel_requested_run_id after the
 * block clears — both requestCancel returning {@code Requested} AND casToPublishing returning {@code
 * true} for the SAME run, which must never both happen.
 */
@QuarkusTest
class SearchProjectionCancelRaceIT {

    @Inject SearchProjectionStateStore store;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @AfterEach
    void cleanup() {
        // None of these tests ever transitions its row out of status = 'building' (only
        // active_run_phase / cancel_requested_run_id change) — left uncleaned, a row would sit
        // 'building' forever and pollute any OTHER test's cross-tenant store.findBuilding() (e.g.
        // SearchProjectionStateStoreTest.findBuildingReturnsOnlyBuildingRowsAcrossTenants matches on
        // searchIndex name alone, tenant-blind) once these run in the same shared DevServices Postgres.
        store.delete(900L, "idx");
        store.delete(900L, "idx2");
        store.delete(901L, "idx3");
    }

    @Test
    void cancelWinningTheRaceBlocksTheCasThenTheCasObservesCancelRequested() throws Exception {
        UUID runId = UUID.randomUUID();
        store.insertBuilding(900L, "idx", runId, Instant.now());

        // requestCancel holds the row lock across its own SELECT...FOR UPDATE + UPDATE; simulate that
        // by driving it on one thread while the CAS attempt waits behind it on another, then asserting
        // ordering via the outcome: once requestCancel COMMITS, casToPublishing must see the
        // now-cancel_requested_run_id-carrying row and fail its own WHERE.
        CancelResult result = store.requestCancel(900L, "idx");
        assertThat(result).isEqualTo(new CancelResult.Requested(runId));

        boolean casSucceeded = store.casToPublishing(900L, "idx", runId);
        assertThat(casSucceeded).isFalse(); // cancel_requested_run_id now equals active_run_id
    }

    @Test
    void casWinningTheRaceMakesTheCancelSeePastCancellablePoint() {
        UUID runId = UUID.randomUUID();
        store.insertBuilding(900L, "idx2", runId, Instant.now());

        boolean casSucceeded = store.casToPublishing(900L, "idx2", runId);
        assertThat(casSucceeded).isTrue();

        CancelResult result = store.requestCancel(900L, "idx2");
        assertThat(result).isEqualTo(new CancelResult.PastCancellablePoint(runId));
    }

    @Test
    void requestCancelGenuinelyBlocksBehindAConcurrentlyHeldRowLock() throws Exception {
        // The genuinely concurrent property: two real threads, one holding the row lock open via an
        // uncommitted transaction, prove requestCancel actually blocks on FOR UPDATE rather than
        // racing past it — SearchProjectionTwoWorkersIT's own "hold the lock on a side connection"
        // idiom, applied to a ROW lock instead of an advisory lock.
        UUID runId = UUID.randomUUID();
        store.insertBuilding(901L, "idx3", runId, Instant.now());

        CountDownLatch holderReady = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Void> holder = pool.submit(() -> {
                try (Connection c = adminDs.getConnection()) {
                    c.setAutoCommit(false);
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT active_run_id FROM core.search_projection_state "
                          + "WHERE tenant_id = ? AND search_index = ? FOR UPDATE")) {
                        ps.setLong(1, 901L);
                        ps.setString(2, "idx3");
                        ps.executeQuery();
                    }
                    holderReady.countDown();
                    releaseHolder.await(5, TimeUnit.SECONDS);
                    c.commit();
                }
                return null;
            });

            assertThat(holderReady.await(5, TimeUnit.SECONDS)).isTrue();
            Future<CancelResult> canceller = pool.submit(() -> store.requestCancel(901L, "idx3"));

            // requestCancel must not have returned yet — it is blocked on the SAME row's FOR UPDATE.
            assertThatThrownBy(() -> canceller.get(300, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

            releaseHolder.countDown();
            holder.get(5, TimeUnit.SECONDS);
            assertThat(canceller.get(5, TimeUnit.SECONDS))
                .isEqualTo(new CancelResult.Requested(runId));
        } finally {
            pool.shutdownNow();
        }
    }

    @RepeatedTest(20)
    void concurrentCancelAndCasNeverBothWinTheSameRun() throws Exception {
        // Genuine two-thread race between the SAME two mechanisms spec test 10 is about — see this
        // class's own javadoc for why this is the test that actually needs FOR UPDATE on the read,
        // where the third test's blocking alone does not: a CyclicBarrier(2) fires both attempts as
        // close to simultaneously as the JVM allows, repeated 20x to give the interleaving a real
        // chance of landing inside the race window every run.
        UUID runId = UUID.randomUUID();
        String idx = "race-" + UUID.randomUUID();
        store.insertBuilding(902L, idx, runId, Instant.now());

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<CancelResult> cancelFuture = pool.submit(() -> {
                barrier.await();
                return store.requestCancel(902L, idx);
            });
            Future<Boolean> casFuture = pool.submit(() -> {
                barrier.await();
                return store.casToPublishing(902L, idx, runId);
            });

            CancelResult cancelResult = cancelFuture.get(5, TimeUnit.SECONDS);
            boolean casResult = casFuture.get(5, TimeUnit.SECONDS);

            boolean cancelWon = cancelResult instanceof CancelResult.Requested;
            assertThat(cancelWon && casResult)
                .as("cancel result=" + cancelResult + " cas=" + casResult
                  + " — cancel and CAS must never BOTH win the same run")
                .isFalse();
            assertThat(cancelWon || casResult)
                .as("exactly one side must win — the row starts building with no other actor")
                .isTrue();
        } finally {
            pool.shutdownNow();
            store.delete(902L, idx);
        }
    }
}
