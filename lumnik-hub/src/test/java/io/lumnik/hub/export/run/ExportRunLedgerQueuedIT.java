package io.lumnik.hub.export.run;

import io.lumnik.hub.audit.AuditRecorder;
import io.lumnik.hub.event.consumer.SchedulerPausedProfile;
import io.lumnik.hub.export.ExportManifestStore;
import io.lumnik.hub.scheduler.TenantScope;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** #952 slice 4: the ledger can claim a Queued row — no start, an accept instant — and a queued row is in flight. */
@QuarkusTest
@TestProfile(SchedulerPausedProfile.class)
class ExportRunLedgerQueuedIT extends ExportQueuedRunITSupport {

    private ExportRunLedger.Claim claim(String name, boolean queued) {
        ExportManifestStore.Row row = row(name);
        ExportRunLedger.NewRun run = new ExportRunLedger.NewRun(TENANT, row.id(), row.name(), row.scope(),
            row.entityName(), row.destinationKind(), AuditRecorder.sha256(row.yaml()), ExportTrigger.MANUAL, 7L,
            null, Instant.now(), 1, null);
        return TenantScope.get(TENANT, () -> QuarkusTransaction.requiringNew().call(() -> ledger.claim(run, queued)));
    }

    @Test
    void a_queued_claim_writes_a_row_that_has_not_started() throws Exception {
        String name = applyExport(unique("lq") + ".csv", false);

        var claimed = (ExportRunLedger.Claimed) claim(name, true);

        var run = runRow(claimed.runId());
        assertThat(run.status()).isEqualTo("Queued");
        assertThat(run.startedAt()).as("not started").isNull();
        assertThat(run.endedAt()).isNull();
        assertThat(run.queuedAt()).as("the accept instant").isNotNull();
    }

    @Test
    void a_running_claim_still_starts_now_and_is_not_queued() throws Exception {
        String name = applyExport(unique("lr") + ".csv", false);

        var claimed = (ExportRunLedger.Claimed) claim(name, false);

        var run = runRow(claimed.runId());
        assertThat(run.status()).isEqualTo("Running");
        assertThat(run.startedAt()).isNotNull();
        assertThat(run.queuedAt()).isNull();
    }

    @Test
    void a_queued_row_counts_as_in_flight_for_the_next_claim_whatever_its_kind() throws Exception {
        String name = applyExport(unique("lf") + ".csv", false);
        var first = (ExportRunLedger.Claimed) claim(name, true);

        assertThat(claim(name, true)).isEqualTo(new ExportRunLedger.AlreadyRunning(first.runId()));
        assertThat(claim(name, false)).as("a Running claim meets the index too")
            .isEqualTo(new ExportRunLedger.AlreadyRunning(first.runId()));
    }
}
