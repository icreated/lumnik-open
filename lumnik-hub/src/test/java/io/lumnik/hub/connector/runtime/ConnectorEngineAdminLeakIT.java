package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.lumnik.hub.event.consumer.SchedulerPausedProfile;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Regression coverage for the admin-connection leak on the "another run already has this
 * endpoint's advisory lock" skip path in {@code ConnectorEngine#doRunEndpoint}: the checkout
 * used only to attempt the lock was never closed when the attempt failed.
 *
 * <p>The scheduler is paused for the whole test: the assertion compares the admin pool's
 * {@code activeCount()} across the skip path, and several scheduled jobs borrow admin
 * connections ({@code ConnectorScheduler} every minute, {@code AuditDispatcher} every two
 * seconds, the dormancy sweep one checkout per document). Any of them checked out at the
 * instant of a reading shifts it, so without this the assertion is a race — narrow, but this
 * suite's only automated gate is the nightly {@code mvn verify}, where a flaky IT rots for a
 * day before anyone sees it.
 */
@QuarkusTest
@TestProfile(SchedulerPausedProfile.class)
class ConnectorEngineAdminLeakIT {

    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorRunRepository runs;
    @Inject UserTransaction utx;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @Test
    void skipped_run_returns_the_admin_connection_to_the_pool() throws Exception {
        UUID connectorId = TenantContext.callAsSystem(() -> {
            utx.begin();
            Connector c = new Connector();
            c.setTenantId(1L);
            c.setName("engine-leak-test-" + System.nanoTime());
            c.setTypeId("hello-world");
            c.setConfig(Map.of("count", 1));
            connectors.persist(c);
            connectors.flush();
            utx.commit();
            return c.getId();
        });

        // First run materializes (and completes on) the connector's synthetic endpoint.
        RunStatus first = engine.runSync(connectorId, new RunTrigger.Manual(1L));
        assertThat(first).isInstanceOf(RunStatus.Completed.class);

        UUID endpointId = TenantContext.callAsSystem(() -> {
            utx.begin();
            var h = runs.findRecentByConnector(connectorId, 1);
            utx.commit();
            return h.get(0).getEndpointId();
        });
        long lockKey = endpointId.getMostSignificantBits() ^ endpointId.getLeastSignificantBits();

        // Simulate a concurrent run already holding the endpoint's advisory lock, on a
        // connection kept open for the duration of the probe (the "other live run"'s session).
        try (Connection holder = adminDs.getConnection()) {
            try (PreparedStatement ps = holder.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                ps.setLong(1, lockKey);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next() && rs.getBoolean(1))
                            .as("test setup: must actually hold the endpoint's advisory lock")
                            .isTrue();
                }
            }

            long baselineActive = adminDs.getMetrics().activeCount();

            RunStatus skipped = engine.runSync(connectorId, new RunTrigger.Manual(2L));

            assertThat(skipped).isInstanceOf(RunStatus.Cancelled.class);
            assertThat(((RunStatus.Cancelled) skipped).reason())
                    .isEqualTo("skipped: another run is already active for this endpoint");

            assertThat(adminDs.getMetrics().activeCount())
                    .as("the admin-pool checkout used for the failed advisory-lock attempt must be closed")
                    .isEqualTo(baselineActive);

            var history = TenantContext.callAsSystem(() -> {
                utx.begin();
                var h = runs.findRecentByConnector(connectorId, 1);
                utx.commit();
                return h;
            });
            assertThat(history.get(0).getStatus())
                    .as("the skip path must still finalize the run as Cancelled")
                    .isEqualTo("Cancelled");
        }
    }
}
