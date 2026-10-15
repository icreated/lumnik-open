package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.lumnik.hub.entity.resolution.ResolutionCoverageStore;
import io.lumnik.hub.event.consumer.SchedulerPausedProfile;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.lumnik.hub.testsupport.EventItSupport.runInTxAsSystem;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #952: a Queued run has no lock and has written nothing. The readers that interpret a run's status
 * must not read it as a live run (the reaper), as the reason a connector is not due (they do: it
 * is in flight), or as ingested data a resolution pass owes a replay.
 */
@QuarkusTest
@TestProfile(SchedulerPausedProfile.class)
class QueuedRunReadersIT {

    private static final long TENANT = 9522L;

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject ConnectorScheduler scheduler;
    @Inject RunReaper reaper;
    @Inject ResolutionCoverageStore coverage;
    @Inject ConnectorRunRepository runs;
    @Inject UserTransaction utx;

    @AfterEach
    void clean() {
        Jdbc.update(adminDs, "clean 952 readers", "DELETE FROM connector.connector WHERE tenant_id = ?",
            ps -> ps.setLong(1, TENANT));   // cascades to the runs
    }

    private UUID connector(String schedule) {
        UUID id = UUID.randomUUID();
        Jdbc.update(adminDs, "seed connector", "INSERT INTO connector.connector (id, tenant_id, name, type_id, config, "
            + "enabled, tags, sync_mode, skip_policy, schedule, created_at) VALUES (?, ?, ?, 'jdbc-generic', '{}'::jsonb, "
            + "true, ARRAY['r952'], 'Incremental', '{\"type\":\"SkipUpTo\",\"maxSkips\":100}'::jsonb, ?, now() - interval '2 days')",
            ps -> { ps.setObject(1, id); ps.setLong(2, TENANT); ps.setString(3, "readers-" + id);
                    ps.setString(4, schedule); });
        return id;
    }

    private UUID queuedRun(UUID connector) {
        UUID id = UUID.randomUUID();
        Jdbc.update(adminDs, "seed queued", "INSERT INTO connector.connector_run (id, tenant_id, connector_id, status, "
            + "sync_mode, started_at, queued_at, triggered_by) VALUES (?, ?, ?, 'Queued', 'Incremental', NULL, now(), 'manual')",
            ps -> { ps.setObject(1, id); ps.setLong(2, TENANT); ps.setObject(3, connector); });
        return id;
    }

    private boolean due(UUID connector) {
        return scheduler.listDueConnectors().stream().anyMatch(d -> d.id().equals(connector));
    }

    @Test
    void a_connector_with_a_queued_run_is_not_due_again() {
        UUID connector = connector("* * * * *");
        assertThat(due(connector)).as("control: with no run in flight it is listed").isTrue();

        queuedRun(connector);

        assertThat(due(connector)).as("a queued run is in flight; the scheduler must not stack another").isFalse();
    }

    @Test
    void the_reaper_leaves_a_queued_row_alone() {
        UUID run = queuedRun(connector(null));

        reaper.reap();

        assertThat(Jdbc.queryOne(adminDs, "status", "SELECT status FROM connector.connector_run WHERE id = ?",
            ps -> ps.setObject(1, run), rs -> rs.getString(1)).orElseThrow()).isEqualTo("Queued");
    }

    @Test
    void a_queued_run_is_never_uncovered_for_resolution() {
        UUID connector = connector(null);
        UUID run = queuedRun(connector);

        assertThat(coverage.uncoveredBound(TENANT, "Readers952"))
            .as("it wrote nothing: it must not pin an entity's asOf").isEqualTo(ResolutionCoverageStore.UncoveredBound.NONE);
        assertThat(coverage.finishedUncoveredRuns(TENANT, "Readers952"))
            .as("nor be replayed as a finished run").doesNotContain(run);

        // Control, so the two assertions above have teeth: the same row, once it has run, IS owed.
        Jdbc.update(adminDs, "finish", "UPDATE connector.connector_run SET status = 'Completed', started_at = now(), "
            + "ended_at = now() WHERE id = ?", ps -> ps.setObject(1, run));
        assertThat(coverage.finishedUncoveredRuns(TENANT, "Readers952")).contains(run);
        assertThat(coverage.uncoveredBound(TENANT, "Readers952").earliestFence()).isNotNull();
    }

    @Test
    void hibernate_loads_a_run_whose_start_is_still_null() throws Exception {
        UUID connector = connector(null);
        UUID run = queuedRun(connector);

        ConnectorRun loaded = runInTxAsSystem(utx, () -> runs.findLatestActiveByConnector(connector)).orElseThrow();

        assertThat(loaded.getId()).isEqualTo(run);
        assertThat(loaded.getStatus()).isEqualTo("Queued");
        assertThat(loaded.getStartedAt()).isNull();
        assertThat(loaded.getQueuedAt()).isNotNull();
    }
}
