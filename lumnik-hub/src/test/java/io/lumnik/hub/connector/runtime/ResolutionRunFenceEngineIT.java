package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.lumnik.hub.event.consumer.SchedulerPausedProfile;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #748 against the REAL engine: the fence really precedes a run's first row, and a run skipped for a
 * held endpoint lock — which never executes — is exempt from the resolution ledger instead of pinning
 * every {@code resolution:} entity's asOf forever. The scheduler is paused (as in
 * {@code ConnectorEngineAdminLeakIT}) so no other run interleaves.
 */
@QuarkusTest
@TestProfile(SchedulerPausedProfile.class)
class ResolutionRunFenceEngineIT {

    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject ConnectorRunRepository runs;
    @Inject UserTransaction utx;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    /** A csv-file connector whose one endpoint lands three rows in {@code connector.t_<name>} (a real dynamic table). */
    private UUID csvConnector(String name) throws Exception {
        Path csv = Files.createTempFile("fence748_", ".csv");
        csv.toFile().deleteOnExit();
        Files.writeString(csv, "row_id,name\n1,Alice\n2,Bob\n3,Carol\n");
        String path = csv.toAbsolutePath().toString();
        return TenantContext.callAsSystem(() -> {
            utx.begin();
            Map<String, Object> cfg = new HashMap<>();
            cfg.put("kind", "csv-file");
            cfg.put("transport", Map.of("kind", "local", "path", path));
            cfg.put("parser", Map.of("has_header", true));
            Connector c = new Connector();
            c.setTenantId(1L);
            c.setName("fence748-engine-" + name);
            c.setTypeId("csv-file");
            c.setConfig(cfg);
            connectors.persist(c);
            connectors.flush();
            ConnectorEndpoint ep = new ConnectorEndpoint();
            ep.setTenantId(1L);
            ep.setConnectorId(c.getId());
            ep.setName("default");
            ep.setMethod("GET");
            ep.setPath(path);
            ep.setEnabled(true);
            ep.setConfig(Map.of("name", name));
            endpoints.persist(ep);
            endpoints.flush();
            utx.commit();
            return c.getId();
        });
    }

    private ConnectorRun newestRun(UUID connectorId) throws Exception {
        return TenantContext.callAsSystem(() -> {
            utx.begin();
            var h = runs.findRecentActiveByConnector(connectorId, 1);
            utx.commit();
            return h.get(0);
        });
    }

    private Instant fence(UUID run) throws Exception {
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT resolution_fence_at FROM connector.connector_run WHERE id = ?")) {
            ps.setObject(1, run);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Timestamp ts = rs.getTimestamp(1);
                return ts == null ? null : ts.toInstant();
            }
        }
    }

    private boolean exempt(UUID run) throws Exception {
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT resolution_exempt FROM connector.connector_run WHERE id = ?")) {
            ps.setObject(1, run);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    /** The earliest {@code _ingested_at} among the rows this run wrote into {@code connector.t_<name>}. */
    private Instant firstRowOf(UUID run, String name) throws Exception {
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT min(_ingested_at) FROM connector.t_" + name + " WHERE _run_id = ?")) {
            ps.setObject(1, run);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Timestamp ts = rs.getTimestamp(1);
                return ts == null ? null : ts.toInstant();
            }
        }
    }

    @Test
    void aRealRunsFencePrecedesItsFirstRow() throws Exception {
        String table = "fence748_real_" + System.nanoTime();
        UUID connectorId = csvConnector(table);
        assertThat(engine.runSync(connectorId, new RunTrigger.Manual(1L))).isInstanceOf(RunStatus.Completed.class);
        UUID run = newestRun(connectorId).getId();

        Instant firstRow = firstRowOf(run, table);
        assertThat(firstRow).as("the run must have written its rows").isNotNull();
        assertThat(fence(run)).isBefore(firstRow);
        assertThat(exempt(run)).as("a run that executed is never exempt").isFalse();
    }

    @Test
    void aRunSkippedForAHeldEndpointLockIsExempt() throws Exception {
        UUID connectorId = csvConnector("fence748_skip_" + System.nanoTime());
        assertThat(engine.runSync(connectorId, new RunTrigger.Manual(1L))).isInstanceOf(RunStatus.Completed.class);
        UUID endpointId = newestRun(connectorId).getEndpointId();
        long lockKey = endpointId.getMostSignificantBits() ^ endpointId.getLeastSignificantBits();

        try (Connection holder = adminDs.getConnection()) {
            try (PreparedStatement ps = holder.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                ps.setLong(1, lockKey);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next() && rs.getBoolean(1)).as("test setup: hold the endpoint lock").isTrue();
                }
            }
            assertThat(engine.runSync(connectorId, new RunTrigger.Manual(2L))).isInstanceOf(RunStatus.Cancelled.class);
            UUID skipped = newestRun(connectorId).getId();

            assertThat(exempt(skipped)).as("a skipped run never executed, so it has nothing to resolve").isTrue();
        }
    }
}
