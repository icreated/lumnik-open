package io.lumnik.hub.search.projection;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.within;

@QuarkusTest
class SearchProjectionStateStoreTest {

    @Inject SearchProjectionStateStore store;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @AfterEach
    void cleanup() throws SQLException {
        store.delete(1L, "idx");
        store.delete(1L, "idx2");
    }

    @Test
    void insertBuildingThenFindRoundTrips() {
        store.insertBuilding(1L, "idx", UUID.randomUUID(), Instant.now());
        Optional<SearchProjectionState> state = store.find(1L, "idx");
        assertThat(state).isPresent();
        assertThat(state.get().status()).isEqualTo("building");
        assertThat(state.get().liveGeneration()).isNull();
        assertThat(state.get().position()).isEqualTo(0);
    }

    @Test
    void markReadyThenMarkDegradedThenTransitionToBuildingClearsLastError() {
        store.insertBuilding(1L, "idx", UUID.randomUUID(), Instant.now());
        store.markDegraded(1L, "idx", "boom");
        assertThat(store.find(1L, "idx").get().lastError()).isEqualTo("boom");

        // This is the exact transition an earlier draft of the design got wrong: a degraded row's
        // own error, carried unchanged into 'building', violates search_projection_state_error_check
        // the instant Postgres enforces it. transitionToBuilding must clear it in the same statement.
        assertThatCode(() -> store.transitionToBuilding(1L, "idx", UUID.randomUUID(), Instant.now()))
            .doesNotThrowAnyException();
        assertThat(store.find(1L, "idx").get().lastError()).isNull();
        assertThat(store.find(1L, "idx").get().status()).isEqualTo("building");
    }

    @Test
    void markReadySetsProviderGenerationAndPosition() {
        store.insertBuilding(1L, "idx", UUID.randomUUID(), Instant.now());
        store.markReady(1L, "idx", "postgres", "gen-1", 42L);
        SearchProjectionState s = store.find(1L, "idx").get();
        assertThat(s.status()).isEqualTo("ready");
        assertThat(s.providerName()).isEqualTo("postgres");
        assertThat(s.liveGeneration()).isEqualTo("gen-1");
        assertThat(s.position()).isEqualTo(42L);
        assertThat(s.retryCount()).isEqualTo(0);
        assertThat(s.lastError()).isNull();
    }

    @Test
    void markReadyNeverRegressesPositionBelowAPriorCatchupAdvance() {
        // #712 follow-up: a late-committing ALL mark with a LOWER id than one already caught up
        // triggers a rebuild whose own watermark sits below the current position. markReady must not
        // drag position back down — it stays the diagnostic-only, never-decreasing counter.
        store.insertBuilding(1L, "idx", UUID.randomUUID(), Instant.now());
        store.advancePosition(1L, "idx", 100L);
        store.markReady(1L, "idx", "postgres", "gen-2", 50L);

        SearchProjectionState s = store.find(1L, "idx").get();
        assertThat(s.position()).isEqualTo(100L);
        assertThat(s.status()).isEqualTo("ready");
        assertThat(s.providerName()).isEqualTo("postgres");
        assertThat(s.liveGeneration()).isEqualTo("gen-2");
    }

    @Test
    void deleteRemovesTheRow() {
        store.insertBuilding(1L, "idx", UUID.randomUUID(), Instant.now());
        store.delete(1L, "idx");
        assertThat(store.find(1L, "idx")).isEmpty();
    }

    @Test
    void findBuildingReturnsOnlyBuildingRowsAcrossTenants() {
        store.insertBuilding(1L, "idx", UUID.randomUUID(), Instant.now());
        store.insertBuilding(1L, "idx2", UUID.randomUUID(), Instant.now());
        store.markReady(1L, "idx2", "postgres", "gen-1", 1L); // no longer building

        List<SearchProjectionState> building = store.findBuilding();
        assertThat(building).extracting(SearchProjectionState::searchIndex).contains("idx");
        assertThat(building).extracting(SearchProjectionState::searchIndex).doesNotContain("idx2");
    }

    @Test
    void degradeIfStillBuildingDegradesABuildingRowAndReturnsTrue() {
        store.insertBuilding(1L, "idx", UUID.randomUUID(), Instant.now());
        boolean degraded = store.degradeIfStillBuilding(1L, "idx", "boom");
        assertThat(degraded).isTrue();
        SearchProjectionState s = store.find(1L, "idx").get();
        assertThat(s.status()).isEqualTo("degraded");
        assertThat(s.lastError()).isEqualTo("boom");
        assertThat(s.retryCount()).isEqualTo(1);
    }

    @Test
    void degradeIfStillBuildingIsANoOpAndReturnsFalseWhenTheRowIsNotBuilding() {
        store.insertBuilding(1L, "idx", UUID.randomUUID(), Instant.now());
        store.markReady(1L, "idx", "postgres", "gen-1", 1L); // status is now 'ready', not 'building'

        boolean degraded = store.degradeIfStillBuilding(1L, "idx", "boom");
        assertThat(degraded).isFalse();
        SearchProjectionState s = store.find(1L, "idx").get();
        assertThat(s.status()).isEqualTo("ready"); // untouched
        assertThat(s.lastError()).isNull();
    }

    @Test
    void insertBuildingClaimsTheRun() {
        UUID runId = UUID.randomUUID();
        Instant startedAt = Instant.now();
        store.insertBuilding(1L, "idx", runId, startedAt);
        SearchProjectionState s = store.find(1L, "idx").get();
        assertThat(s.activeRunId()).isEqualTo(runId);
        assertThat(s.activeRunPhase()).isEqualTo("building");
        assertThat(s.activeRunStartedAt()).isCloseTo(startedAt, within(1, ChronoUnit.SECONDS));
    }

    @Test
    void markReadyEndingRunClearsTheActiveRunAndStampsLastRebuild() throws SQLException {
        UUID runId = UUID.randomUUID();
        store.insertBuilding(1L, "idx", runId, Instant.now());
        try (Connection c = adminDs.getConnection()) {
            store.markReady(c, 1L, "idx", "postgres", "gen-1", 42L, runId);
        }
        SearchProjectionState s = store.find(1L, "idx").get();
        assertThat(s.status()).isEqualTo("ready");
        assertThat(s.activeRunId()).isNull();
        assertThat(s.lastRebuildRunId()).isEqualTo(runId);
        assertThat(s.lastRebuildOutcome()).isEqualTo("ready");
        assertThat(s.lastRebuildFinishedAt()).isNotNull();
    }

    @Test
    void markReadyEndingRunClearsAStaleDocumentCountEvenToAFreshGeneration() throws SQLException {
        // Spec test 7: a successful rebuild must never leave the PRIOR generation's count looking
        // current, even though live_generation goes from one non-null value to another non-null value.
        UUID firstRun = UUID.randomUUID();
        store.insertBuilding(1L, "idx", firstRun, Instant.now());
        try (Connection c = adminDs.getConnection()) {
            store.markReady(c, 1L, "idx", "postgres", "gen-1", 1L, firstRun);
        }
        // A GET caching a fresh count for gen-1 (Task 9's own updateDocumentCount).
        store.updateDocumentCount(1L, "idx", 100L, "gen-1");

        UUID secondRun = UUID.randomUUID();
        store.transitionToBuilding(1L, "idx", secondRun, Instant.now());
        try (Connection c = adminDs.getConnection()) {
            store.markReady(c, 1L, "idx", "postgres", "gen-2", 2L, secondRun);
        }
        SearchProjectionState s = store.find(1L, "idx").get();
        assertThat(s.liveGeneration()).isEqualTo("gen-2");
        assertThat(s.lastDocumentCount()).isNull();
        assertThat(s.lastDocumentCountAt()).isNull();
    }

    @Test
    void updateDocumentCountIsSilentlyDroppedWhenTheExpectedGenerationNoLongerMatches() throws SQLException {
        // Code review finding: a GET reads a live count for generation G1, then a concurrent cutover
        // to G2 lands (clearing the document-count cache as part of that same atomic write) before
        // the GET's own write — writing the stale G1 count back afterward would serve it as though it
        // belonged to G2. The guard: bind the write to the generation the caller observed BEFORE its
        // own live count call; a mismatch (including "cutover already happened") is a silent no-op.
        UUID firstRun = UUID.randomUUID();
        store.insertBuilding(1L, "idx", firstRun, Instant.now());
        try (Connection c = adminDs.getConnection()) {
            store.markReady(c, 1L, "idx", "postgres", "gen-1", 1L, firstRun);
        }

        // The cutover to gen-2 already happened by the time this stale write lands.
        UUID secondRun = UUID.randomUUID();
        store.transitionToBuilding(1L, "idx", secondRun, Instant.now());
        try (Connection c = adminDs.getConnection()) {
            store.markReady(c, 1L, "idx", "postgres", "gen-2", 2L, secondRun);
        }

        // Still bound to the now-stale "gen-1" the caller observed before the cutover — zero rows
        // affected, no exception, and the row's own count is left exactly as markReady left it (null).
        store.updateDocumentCount(1L, "idx", 999L, "gen-1");

        SearchProjectionState s = store.find(1L, "idx").get();
        assertThat(s.liveGeneration()).isEqualTo("gen-2");
        assertThat(s.lastDocumentCount()).isNull();
        assertThat(s.lastDocumentCountAt()).isNull();
    }

    @Test
    void updateDocumentCountIsRefusedWhileACutoverIsInFlight() throws SQLException {
        // Code review finding: sink.publish(gen-2) runs BEFORE markReady(gen-2) moves live_generation
        // forward, so a GET that read "gen-1" and whose own live call landed once the pair entered
        // 'publishing' can be holding gen-2's count while live_generation STILL reads "gen-1" — the
        // generation-only guard above cannot see this, since the column genuinely hasn't moved yet.
        UUID firstRun = UUID.randomUUID();
        store.insertBuilding(1L, "idx", firstRun, Instant.now());
        try (Connection c = adminDs.getConnection()) {
            store.markReady(c, 1L, "idx", "postgres", "gen-1", 1L, firstRun);
        }

        // A second run claims the pair and cuts over to gen-2 — casToPublishing alone, standing in
        // for "sink.publish(gen-2) may already have run at the provider", deliberately WITHOUT
        // calling markReady: live_generation is still "gen-1" at this exact instant.
        UUID secondRun = UUID.randomUUID();
        store.transitionToBuilding(1L, "idx", secondRun, Instant.now());
        assertThat(store.casToPublishing(1L, "idx", secondRun)).isTrue();

        // Still bound to "gen-1" — live_generation alone would still match, but the pair is mid-cutover.
        boolean written = store.updateDocumentCount(1L, "idx", 999L, "gen-1");

        assertThat(written).isFalse();
        SearchProjectionState s = store.find(1L, "idx").get();
        assertThat(s.liveGeneration()).isEqualTo("gen-1"); // markReady never ran — unchanged
        assertThat(s.lastDocumentCount()).isNull();
        assertThat(s.lastDocumentCountAt()).isNull();
    }

    @Test
    void markDegradedClearingGenerationClearsTheDocumentCountToo() {
        UUID runId = UUID.randomUUID();
        store.insertBuilding(1L, "idx", runId, Instant.now());
        store.markDegradedClearingGeneration(1L, "idx", "boom", runId);
        SearchProjectionState s = store.find(1L, "idx").get();
        assertThat(s.status()).isEqualTo("degraded");
        assertThat(s.liveGeneration()).isNull();
        assertThat(s.lastDocumentCount()).isNull();
        assertThat(s.lastRebuildOutcome()).isEqualTo("failed");
    }

    @Test
    void casToPublishingSucceedsOnceThenFailsForARepeatCall() {
        UUID runId = UUID.randomUUID();
        store.insertBuilding(1L, "idx", runId, Instant.now());
        assertThat(store.casToPublishing(1L, "idx", runId)).isTrue();
        assertThat(store.find(1L, "idx").get().activeRunPhase()).isEqualTo("publishing");
        assertThat(store.casToPublishing(1L, "idx", runId)).isFalse(); // no longer 'building'
    }

    @Test
    void markCancelledRevertsToReadyOnlyWhenAPriorGenerationExisted() {
        UUID firstRun = UUID.randomUUID();
        store.insertBuilding(1L, "idx", firstRun, Instant.now());
        store.markCancelled(1L, "idx", firstRun, false); // no prior generation
        assertThat(store.find(1L, "idx").get().status()).isEqualTo("cancelled");

        UUID secondRun = UUID.randomUUID();
        store.transitionToBuilding(1L, "idx", secondRun, Instant.now());
        store.markCancelled(1L, "idx", secondRun, true); // a prior generation existed
        SearchProjectionState s = store.find(1L, "idx").get();
        assertThat(s.status()).isEqualTo("ready");
        assertThat(s.lastRebuildOutcome()).isEqualTo("cancelled"); // outcome is 'cancelled' either way
    }

    @Test
    void discoveryDoesNotFilterOutACancelledFirstEverRebuild() {
        // #703: status = 'cancelled' must never be treated like an excluded/terminal-forever status by
        // ANY admin-wide query discoveryTick relies on — not merely by liveGeneration() == null (already
        // true here), which a DIFFERENT status-based filter elsewhere could silently defeat.
        store.insertBuilding(1L, "idx", UUID.randomUUID(), Instant.now());
        store.markCancelled(1L, "idx", store.find(1L, "idx").get().activeRunId(), false);

        assertThat(store.find(1L, "idx").get().status()).isEqualTo("cancelled");
        assertThat(store.findAllAdmin().get(1L)).containsKey("idx");
    }

    @Test
    void requestCancelReturnsNoActiveRunWhenThereIsNoStateRowAtAll() {
        // Review Focus: SELECT ... FOR UPDATE on zero rows locks nothing and must not be mistaken for success.
        assertThat(store.requestCancel(1L, "never-built")).isInstanceOf(CancelResult.NoActiveRun.class);
    }

    @Test
    void requestCancelReturnsNoActiveRunWhenTheRowHasNoActiveRun() {
        store.insertBuilding(1L, "idx", UUID.randomUUID(), Instant.now());
        store.markCancelled(1L, "idx", store.find(1L, "idx").get().activeRunId(), false); // now terminal, cancelled
        assertThat(store.requestCancel(1L, "idx")).isInstanceOf(CancelResult.NoActiveRun.class);
    }

    @Test
    void requestCancelReturnsPastCancellablePointWhenAlreadyPublishing() {
        UUID runId = UUID.randomUUID();
        store.insertBuilding(1L, "idx", runId, Instant.now());
        store.casToPublishing(1L, "idx", runId);
        CancelResult result = store.requestCancel(1L, "idx");
        assertThat(result).isInstanceOf(CancelResult.PastCancellablePoint.class);
        assertThat(((CancelResult.PastCancellablePoint) result).runId()).isEqualTo(runId);
    }

    @Test
    void requestCancelIsRequestedAndIdempotentOnRepeatCalls() {
        // Review Focus: a second cancel of the same run is still 202 Requested, never a 409.
        UUID runId = UUID.randomUUID();
        store.insertBuilding(1L, "idx", runId, Instant.now());
        assertThat(store.requestCancel(1L, "idx")).isEqualTo(new CancelResult.Requested(runId));
        assertThat(store.requestCancel(1L, "idx")).isEqualTo(new CancelResult.Requested(runId));
        assertThat(store.find(1L, "idx").get().cancelRequestedRunId()).isEqualTo(runId);
    }
}
