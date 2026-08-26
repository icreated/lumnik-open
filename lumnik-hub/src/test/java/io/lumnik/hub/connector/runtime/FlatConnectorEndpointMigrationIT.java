package io.lumnik.hub.connector.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.connector.repository.ConnectorCursorEntityRepository;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.lumnik.hub.testsupport.EventItSupport;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD test for {@link FlatConnectorEndpointMigration}.
 *
 * <p>Seeds a legacy flat {@code jdbc-generic} connector (flat config with {@code table-name},
 * {@code table-schema}, etc.) and a matching {@code connector_cursor} row, then invokes
 * {@code migrateAll()} explicitly, and asserts:
 * <ol>
 *   <li>One {@code connector_endpoint} was created with the unified config keys.</li>
 *   <li>The cursor row's {@code endpointId} now points to the new endpoint.</li>
 *   <li>The migration is idempotent (re-running does not create a second endpoint).</li>
 * </ol>
 */
@QuarkusTest
class FlatConnectorEndpointMigrationIT {

    @Inject FlatConnectorEndpointMigration migration;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject ConnectorCursorEntityRepository cursors;
    @Inject ConnectorRunRepository runs;
    @Inject ObjectMapper mapper;
    @Inject UserTransaction utx;

    @Test
    void migrates_flat_jdbc_connector_to_synthetic_endpoint() throws Exception {
        // ── Seed a legacy flat connector ──────────────────────────────────────────
        UUID connectorId = EventItSupport.runInTxAsSystem(utx, () -> {
            Connector c = new Connector();
            c.setTenantId(1L);
            c.setName("legacy-jdbc-" + System.nanoTime());
            c.setTypeId("jdbc-generic");
            c.setConfig(Map.of(
                "url", "jdbc:postgresql://localhost:5432/test",
                "user", "appuser",
                "password", "secret",
                "dialect", "postgres",
                "table-schema", "public",
                "table-name", "src_x",
                "mode", "full",
                "pk-columns", List.of("src_x_id"),
                "chunk-size", 500
            ));
            connectors.persist(c);
            connectors.flush();
            return c.getId();
        });

        // ── Invoke migration ──────────────────────────────────────────────────────
        TenantContext.runAsSystem(migration::migrateAll);

        // ── Assert: exactly one endpoint was created ──────────────────────────────
        List<ConnectorEndpoint> createdEndpoints = EventItSupport.runInTxAsSystem(utx, () ->
            endpoints.listByConnector(connectorId)
        );
        assertThat(createdEndpoints)
            .as("exactly one synthetic endpoint must be created")
            .hasSize(1);

        ConnectorEndpoint ep = createdEndpoints.get(0);
        assertThat(ep.getName())
            .as("endpoint name = the source table name")
            .isEqualTo("src_x");
        assertThat(ep.getMethod()).isEqualTo("GET");
        assertThat(ep.getPath()).isEqualTo("/src_x");
        assertThat(ep.isEnabled()).isTrue();

        // ── Assert: config has unified keys ───────────────────────────────────────
        Map<String, Object> epConfig = ep.getConfig();
        assertThat(epConfig)
            .as("endpoint config must contain unified schema key")
            .containsEntry("schema", "public");
        assertThat(epConfig)
            .as("endpoint config must contain unified name key")
            .containsEntry("name", "src_x");
        assertThat(epConfig)
            .as("endpoint config must contain mode")
            .containsEntry("mode", "full");
        assertThat(epConfig.get("pk_columns"))
            .as("pk_columns must be translated from pk-columns")
            .isNotNull();
        assertThat(epConfig.get("chunk_size"))
            .as("chunk_size must be translated from chunk-size")
            .isNotNull();
        assertThat(epConfig)
            .as("watermark_column must not appear when absent in source")
            .doesNotContainKey("watermark_column");

        // ── Assert: idempotent — second run creates no additional endpoint ─────────
        TenantContext.runAsSystem(migration::migrateAll);
        List<ConnectorEndpoint> afterSecondRun = EventItSupport.runInTxAsSystem(utx, () ->
            endpoints.listByConnector(connectorId)
        );
        assertThat(afterSecondRun)
            .as("re-running migration must not create duplicate endpoints")
            .hasSize(1);
    }

    @Test
    void run_endpoint_id_is_backfilled_for_existing_runs() throws Exception {
        // ── Seed connector + existing run with no endpoint_id ─────────────────────
        UUID connectorId = EventItSupport.runInTxAsSystem(utx, () -> {
            Connector c = new Connector();
            c.setTenantId(1L);
            c.setName("legacy-run-" + System.nanoTime());
            c.setTypeId("jdbc-generic");
            c.setConfig(Map.of(
                "url", "jdbc:postgresql://localhost:5432/test",
                "user", "u", "password", "p", "dialect", "postgres",
                "table-schema", "public", "table-name", "src_y",
                "mode", "full", "pk-columns", List.of("src_y_id"), "chunk-size", 100
            ));
            connectors.persist(c);
            connectors.flush();
            return c.getId();
        });

        UUID runId = EventItSupport.runInTxAsSystem(utx, () -> {
            ConnectorRun run = new ConnectorRun();
            run.setTenantId(1L);
            run.setConnectorId(connectorId);
            run.setStatus("Completed");
            run.setSyncMode("FullRefresh");
            run.setStartedAt(Instant.now().minusSeconds(60));
            run.setTriggeredBy("MANUAL");
            runs.persist(run);
            runs.flush();
            return run.getId();
        });

        // ── Run migration ─────────────────────────────────────────────────────────
        TenantContext.runAsSystem(migration::migrateAll);

        // ── Verify the run's endpoint_id is set ───────────────────────────────────
        ConnectorRun updatedRun = EventItSupport.runInTxAsSystem(utx, () ->
            runs.findById(runId)
        );
        List<ConnectorEndpoint> eps = EventItSupport.runInTxAsSystem(utx, () ->
            endpoints.listByConnector(connectorId)
        );
        assertThat(eps).hasSize(1);
        assertThat(updatedRun.getEndpointId())
            .as("existing run must be linked to the new endpoint after migration")
            .isEqualTo(eps.get(0).getId());
    }

    @Test
    void translates_watermark_column_and_drops_hyphenated_keys() throws Exception {
        UUID connectorId = EventItSupport.runInTxAsSystem(utx, () -> {
            Connector c = new Connector();
            c.setTenantId(1L);
            c.setName("legacy-wm-" + System.nanoTime());
            c.setTypeId("jdbc-generic");
            c.setConfig(Map.of(
                "url", "jdbc:postgresql://localhost:5432/test",
                "user", "u", "password", "p", "dialect", "postgres",
                "table-schema", "public", "table-name", "src_w",
                "mode", "watermark", "watermark-column", "updated",
                "pk-columns", List.of("src_w_id"), "chunk-size", 250
            ));
            connectors.persist(c);
            connectors.flush();
            return c.getId();
        });

        TenantContext.runAsSystem(migration::migrateAll);

        ConnectorEndpoint ep = EventItSupport.runInTxAsSystem(utx, () ->
            endpoints.listByConnector(connectorId)).get(0);
        Map<String, Object> cfg = ep.getConfig();
        assertThat(cfg).as("watermark-column translated to watermark_column")
            .containsEntry("watermark_column", "updated")
            .containsEntry("mode", "watermark")
            .containsEntry("schema", "public");
        assertThat(cfg).as("old hyphenated keys removed")
            .doesNotContainKeys("table-schema", "table-name", "pk-columns", "watermark-column", "chunk-size");
    }

    @Test
    void skips_connector_without_table_name() throws Exception {
        UUID connectorId = EventItSupport.runInTxAsSystem(utx, () -> {
            Connector c = new Connector();
            c.setTenantId(1L);
            c.setName("hello-" + System.nanoTime());
            c.setTypeId("hello-world");
            c.setConfig(Map.of("count", 1));   // no table-name → not flat → skipped
            connectors.persist(c);
            connectors.flush();
            return c.getId();
        });

        TenantContext.runAsSystem(migration::migrateAll);

        List<ConnectorEndpoint> eps = EventItSupport.runInTxAsSystem(utx, () ->
            endpoints.listByConnector(connectorId));
        assertThat(eps).as("a connector without table-name must not get a synthetic endpoint").isEmpty();
    }
}
