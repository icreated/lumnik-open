package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.lumnik.hub.event.consumer.SchedulerPausedProfile;
import io.lumnik.hub.run.RunLock;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #952 (found in review): V68's unique index counts {@code Running}, so a refused run — one born while a
 * live run holds the endpoint — can no longer be INSERTed as {@code Running} and ended afterwards. With a
 * real {@code Running} row behind the busy lock (what the older skip tests lacked: they held a bare lock),
 * the second INSERT violated the index and the door answered a 500. The refused row is born terminal.
 */
@QuarkusTest
@TestProfile(SchedulerPausedProfile.class)
class ConnectorEngineSkipBehindLiveRunIT {

    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorRunRepository runs;
    @Inject UserTransaction utx;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @Test
    void a_second_run_behind_a_live_running_row_is_skipped_not_crashed() throws Exception {
        UUID connectorId = TenantContext.callAsSystem(() -> {
            utx.begin();
            Connector c = new Connector();
            c.setTenantId(1L);
            c.setName("skip-behind-live-" + System.nanoTime());
            c.setTypeId("hello-world");
            c.setConfig(Map.of("count", 1));
            connectors.persist(c);
            connectors.flush();
            utx.commit();
            return c.getId();
        });
        assertThat(engine.runSync(connectorId, new RunTrigger.Manual(1L))).isInstanceOf(RunStatus.Completed.class);
        UUID endpointId = TenantContext.callAsSystem(() -> {
            utx.begin();
            var h = runs.findRecentActiveByConnector(connectorId, 1);
            utx.commit();
            return h.get(0).getEndpointId();
        });
        // The live run: a real Running row AND its lock.
        Jdbc.update(adminDs, "live run", "INSERT INTO connector.connector_run (tenant_id, connector_id, endpoint_id, "
            + "status, sync_mode, started_at, triggered_by) VALUES (1, ?, ?, 'Running', 'Incremental', now(), 'manual')",
            ps -> { ps.setObject(1, connectorId); ps.setObject(2, endpointId); });

        try (RunLock live = RunLock.tryAcquire(adminDs, endpointId)) {
            assertThat(live).as("test setup: hold the endpoint's lock").isNotNull();

            RunStatus skipped = engine.runSync(connectorId, new RunTrigger.Manual(2L));

            assertThat(skipped).isInstanceOf(RunStatus.Cancelled.class);
            assertThat(((RunStatus.Cancelled) skipped).reason())
                .isEqualTo("skipped: another run is already active for this endpoint");
        }
        var row = Jdbc.queryOne(adminDs, "refused row", "SELECT status, ended_at IS NOT NULL, resolution_exempt, started_at = ended_at "
            + "FROM connector.connector_run WHERE endpoint_id = ? AND error_message LIKE 'skipped:%'",
            ps -> ps.setObject(1, endpointId),
            rs -> rs.getString(1) + "/" + rs.getBoolean(2) + "/" + rs.getBoolean(3) + "/" + rs.getBoolean(4));
        assertThat(row).as("a run that never ran has a zero-length span: started_at = ended_at")
            .contains("Cancelled/true/true/true");
        Jdbc.update(adminDs, "cleanup", "DELETE FROM connector.connector WHERE id = ?", ps -> ps.setObject(1, connectorId));
    }

    /**
     * #952 (code review): a replica died mid-run, so its Running row is still there but nobody holds its lock.
     * Before V68 the next start simply inserted a second Running row; now the unique index refuses it, and the
     * door would answer 500 until the reaper's next tick. Holding the lock proves the old row is abandoned, so
     * the start fails it the way the reaper does, then runs.
     */
    @Test
    void a_start_over_a_zombie_running_row_fails_it_and_runs() throws Exception {
        UUID connectorId = TenantContext.callAsSystem(() -> {
            utx.begin();
            Connector c = new Connector();
            c.setTenantId(1L);
            c.setName("zombie-" + System.nanoTime());
            c.setTypeId("hello-world");
            c.setConfig(Map.of("count", 1));
            connectors.persist(c);
            connectors.flush();
            utx.commit();
            return c.getId();
        });
        assertThat(engine.runSync(connectorId, new RunTrigger.Manual(1L))).isInstanceOf(RunStatus.Completed.class);
        UUID endpointId = TenantContext.callAsSystem(() -> {
            utx.begin();
            var h = runs.findRecentActiveByConnector(connectorId, 1);
            utx.commit();
            return h.get(0).getEndpointId();
        });
        Jdbc.update(adminDs, "zombie", "INSERT INTO connector.connector_run (tenant_id, connector_id, endpoint_id, "
            + "status, sync_mode, started_at, triggered_by) VALUES (1, ?, ?, 'Running', 'Incremental', now() - interval '1 hour', 'manual')",
            ps -> { ps.setObject(1, connectorId); ps.setObject(2, endpointId); });

        RunStatus second = engine.runSync(connectorId, new RunTrigger.Manual(2L));

        assertThat(second).isInstanceOf(RunStatus.Completed.class);
        var failed = Jdbc.queryOne(adminDs, "zombie row", "SELECT status, error_message FROM connector.connector_run "
            + "WHERE endpoint_id = ? AND started_at < now() - interval '30 minutes'",
            ps -> ps.setObject(1, endpointId), rs -> rs.getString(1) + "|" + rs.getString(2));
        assertThat(failed).contains("Failed|" + RunReaper.REASON);
        Jdbc.update(adminDs, "cleanup", "DELETE FROM connector.connector WHERE id = ?", ps -> ps.setObject(1, connectorId));
    }
}
