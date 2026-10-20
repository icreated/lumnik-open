package io.lumnik.hub.export.run;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.export.SnapshotStats;
import io.lumnik.hub.export.destination.PublishedArtifact;
import io.lumnik.hub.export.run.ExportRunLedger.AlreadyRunning;
import io.lumnik.hub.export.run.ExportRunLedger.Claim;
import io.lumnik.hub.export.run.ExportRunLedger.Claimed;
import io.lumnik.hub.export.run.ExportRunLedger.NewRun;
import io.lumnik.hub.export.run.ExportRunLedger.OccurrenceTaken;
import io.lumnik.hub.export.run.ExportRunLedger.RetrySeed;
import io.lumnik.hub.scheduler.TenantScope;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The ledger's guarantees are database guarantees: the unique indexes decide claims and the
 * V54 CHECKs refuse half-written rows. Each test uses its own export name so runs never collide
 * across tests sharing the DevServices database.
 */
@QuarkusTest
class ExportRunLedgerIT {

    static final long TENANT = 1L;
    static final Instant OCCURRENCE = Instant.parse("2026-09-24T04:00:00Z");

    @Inject ExportRunLedger ledger;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    static <T> T inTenant(long tenant, Supplier<T> work) {
        return TenantScope.get(tenant, () -> QuarkusTransaction.requiringNew().call(work::get));
    }

    static NewRun manual(long tenant, String name) {
        return new NewRun(tenant, UUID.randomUUID(), name, "sales", "Customer", "sftp",
            "ab".repeat(32), ExportTrigger.MANUAL, 7L, null, Instant.now(), 1, null);
    }

    static NewRun scheduled(String name, int attempt) {
        return new NewRun(TENANT, UUID.randomUUID(), name, "sales", "Customer", "sftp",
            "ab".repeat(32), ExportTrigger.SCHEDULE, null, OCCURRENCE, OCCURRENCE, attempt, null);
    }

    static final SnapshotStats STATS = new SnapshotStats(3, 42, "cd".repeat(32));
    static final PublishedArtifact ARTIFACT = new PublishedArtifact("sftp", "/out/c.csv", null, null);

    UUID claimed(Claim c) {
        assertThat(c).isInstanceOf(Claimed.class);
        return ((Claimed) c).runId();
    }

    @Test
    void aSecondClaimWhileOneIsInFlightPointsAtTheRunningOne() {
        UUID first = claimed(inTenant(TENANT, () -> ledger.claim(manual(TENANT, "ledger-inflight"))));
        Claim second = inTenant(TENANT, () -> ledger.claim(manual(TENANT, "ledger-inflight")));
        assertThat(second).isEqualTo(new AlreadyRunning(first));
    }

    @Test
    void theSameOccurrenceAndAttemptIsClaimedOnce() {
        UUID first = claimed(inTenant(TENANT, () -> ledger.claim(scheduled("ledger-occ", 1))));
        inTenant(TENANT, () -> ledger.fail(first, Instant.now(), ExportFailureCode.PUBLICATION, "down"));
        assertThat(inTenant(TENANT, () -> ledger.claim(scheduled("ledger-occ", 1))))
            .isInstanceOf(OccurrenceTaken.class);
    }

    @Test
    void anOccurrenceIsDeliveredAtMostOnce() {
        UUID first = claimed(inTenant(TENANT, () -> ledger.claim(scheduled("ledger-once", 1))));
        assertThat(inTenant(TENANT, () -> ledger.complete(first, Instant.now(), STATS, ARTIFACT))).isTrue();
        // A retry (attempt 2) of a delivered occurrence is refused at claim time, not at complete.
        assertThat(inTenant(TENANT, () -> ledger.claim(scheduled("ledger-once", 2))))
            .isInstanceOf(OccurrenceTaken.class);
    }

    @Test
    void aRetryCountsAttemptsOverItsWholeLineage() {
        String name = "ledger-lineage";
        UUID first = claimed(inTenant(TENANT, () -> ledger.claim(manual(TENANT, name))));
        inTenant(TENANT, () -> ledger.fail(first, Instant.now(), ExportFailureCode.PUBLICATION, "x"));
        UUID second = claimed(inTenant(TENANT, () -> ledger.claim(retry(name, 2, first))));
        inTenant(TENANT, () -> ledger.fail(second, Instant.now(), ExportFailureCode.PUBLICATION, "x"));
        UUID third = claimed(inTenant(TENANT, () -> ledger.claim(retry(name, 3, second))));

        // Retrying any member of the lineage, even the first, goes one past its highest attempt.
        RetrySeed seed = inTenant(TENANT, () -> ledger.retrySeed(TENANT, name, first)).orElseThrow();
        assertThat(seed.nextAttempt()).isEqualTo(4);
        assertThat(seed.status()).isEqualTo(ExportRunStatus.FAILED);
        inTenant(TENANT, () -> ledger.complete(third, Instant.now(), STATS, ARTIFACT));
        assertThat(inTenant(TENANT, () -> ledger.retrySeed(TENANT, name, third)).orElseThrow().status())
            .isEqualTo(ExportRunStatus.COMPLETED);
        assertThat(rootOf(second)).isEqualTo(first);
        assertThat(rootOf(third)).isEqualTo(first);
        assertThat(rootOf(first)).isNull();
    }

    static NewRun retry(String name, int attempt, UUID retryOf) {
        return new NewRun(TENANT, UUID.randomUUID(), name, "sales", "Customer", "sftp",
            "ab".repeat(32), ExportTrigger.MANUAL, 7L, null, Instant.now(), attempt, retryOf);
    }

    /** The run's root_run_id; null for a lineage root. The row itself must exist. */
    private UUID rootOf(UUID run) {
        return Jdbc.queryOne(adminDs, "rootOf " + run,
                "SELECT root_run_id FROM connector.export_run WHERE id = ?",
                ps -> ps.setObject(1, run),
                rs -> Optional.ofNullable(rs.getObject(1, UUID.class)))
            .orElseThrow(() -> new AssertionError("no export_run row " + run))
            .orElse(null);
    }

    @Test
    void completeAfterFailMovesNothing() {
        UUID run = claimed(inTenant(TENANT, () -> ledger.claim(manual(TENANT, "ledger-late"))));
        assertThat(inTenant(TENANT, () -> ledger.fail(run, Instant.now(), ExportFailureCode.INTERRUPTED, "reaped"))).isTrue();
        assertThat(inTenant(TENANT, () -> ledger.complete(run, Instant.now(), STATS, ARTIFACT))).isFalse();

        ExportRunView after = inTenant(TENANT, () -> ledger.find(TENANT, "ledger-late", run)).orElseThrow();
        assertThat(after.status()).isEqualTo(ExportRunStatus.FAILED);
        assertThat(after.failureCode()).isEqualTo(ExportFailureCode.INTERRUPTED);
        assertThat(after.sha256()).isNull();
        assertThat(inTenant(TENANT, () -> ledger.list(TENANT, "ledger-late", 50)))
            .extracting(ExportRunView::id).containsExactly(run);
    }

    @Test
    void theDatabaseRefusesACompletedWithoutReceiptAndAnUnknownStatus() {
        UUID run = claimed(inTenant(TENANT, () -> ledger.claim(manual(TENANT, "ledger-check"))));
        assertThatThrownBy(() -> rawUpdate(
                "UPDATE connector.export_run SET status = ?, ended_at = now() WHERE id = ?",
                ExportRunStatus.COMPLETED.sqlValue(), run))
            .isInstanceOf(RuntimeException.class).hasCauseInstanceOf(SQLException.class)
            .hasMessageContaining("SQLSTATE 23514"); // check_violation
        assertThatThrownBy(() -> rawUpdate(
                "UPDATE connector.export_run SET status = ?, ended_at = now() WHERE id = ?",
                "Delivered", run))
            .isInstanceOf(RuntimeException.class).hasCauseInstanceOf(SQLException.class)
            .hasMessageContaining("SQLSTATE 23514"); // check_violation
    }

    @Test
    void listIsNewestFirst() {
        UUID a = claimed(inTenant(TENANT, () -> ledger.claim(manual(TENANT, "ledger-order"))));
        inTenant(TENANT, () -> ledger.fail(a, Instant.now(), ExportFailureCode.GENERATION, "x"));
        UUID b = claimed(inTenant(TENANT, () -> ledger.claim(manual(TENANT, "ledger-order"))));
        assertThat(inTenant(TENANT, () -> ledger.list(TENANT, "ledger-order", 50)))
            .extracting(ExportRunView::id).containsExactly(b, a);
        assertThat(inTenant(TENANT, () -> ledger.find(TENANT, "ledger-order", b)).orElseThrow().startedAt())
            .isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
    }

    private void rawUpdate(String sql, String status, UUID id) {
        Jdbc.update(adminDs, "rawUpdate " + id, sql, ps -> {
            ps.setString(1, status);
            ps.setObject(2, id);
        });
    }
}
