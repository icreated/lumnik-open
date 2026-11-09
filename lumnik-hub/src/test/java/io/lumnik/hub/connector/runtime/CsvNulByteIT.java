package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.repository.ConnectorDeadLetterRepository;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A NUL byte in a value is that row's problem (#1855): PostgreSQL refuses it in {@code text} (22021), and the
 * refusal used to fail the whole chunk, then the run, whose own failure text could not be stored either. The row is
 * dead-lettered, the others land, the run is not {@code Failed}.
 */
@QuarkusTest
class CsvNulByteIT {

    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject ConnectorDeadLetterRepository dlqRepo;
    @Inject UserTransaction utx;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    @BeforeEach
    void dropTable() {
        execSql("DROP TABLE IF EXISTS connector.t_nul_byte");
    }

    @Test
    void a_row_holding_a_nul_byte_is_dead_lettered_and_the_run_does_not_fail() throws Exception {
        Path csv = Files.createTempFile("nul_byte_", ".csv");
        csv.toFile().deleteOnExit();
        Files.writeString(csv, "row_id,packed\n1,ab\n2,a\u0000b\n3,cd\n");

        UUID connectorId = persistCsvConnector(
            "nul-byte",
            Map.of("kind", "local", "path", csv.toAbsolutePath().toString()),
            Map.of("has_header", true),
            "nul_byte"
        );

        RunStatus status = engine.runSync(connectorId, new RunTrigger.Manual(1L));

        assertThat(status).isNotInstanceOf(RunStatus.Failed.class);
        assertThat(scalar("SELECT count(*) FROM connector.t_nul_byte")).isEqualTo(2L);
        var pending = TenantContext.callAsSystem(() -> dlqRepo.findPendingByConnector(connectorId, 10));
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getErrorMessage()).contains("NUL byte").contains("remove-control-chars");
        long in = scalar("SELECT records_in FROM connector.connector_run WHERE connector_id = '" + connectorId + "'");
        long out = scalar("SELECT records_out FROM connector.connector_run WHERE connector_id = '" + connectorId + "'");
        long skipped = scalar("SELECT records_skipped FROM connector.connector_run WHERE connector_id = '" + connectorId + "'");
        assertThat(in).isEqualTo(3L).isEqualTo(out + skipped);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private UUID persistCsvConnector(
        String connectorName,
        Map<String, Object> transport,
        Map<String, Object> parser,
        String table
    ) {
        return TenantContext.callAsSystem(() -> {
            try {
                utx.begin();

                Map<String, Object> cfg = new HashMap<>();
                cfg.put("kind", "csv-file");
                cfg.put("transport", transport);
                cfg.put("parser", parser);

                Connector c = new Connector();
                c.setTenantId(1L);
                c.setName(connectorName + "-" + System.nanoTime());
                c.setTypeId("csv-file");
                c.setConfig(cfg);
                connectors.persist(c);
                connectors.flush();

                ConnectorEndpoint ep = new ConnectorEndpoint();
                ep.setTenantId(1L);
                ep.setConnectorId(c.getId());
                ep.setName("default");
                ep.setMethod("GET");
                ep.setPath(transport.getOrDefault("path", "").toString());
                ep.setEnabled(true);
                ep.setConfig(Map.of("name", table));
                endpoints.persist(ep);
                endpoints.flush();

                utx.commit();
                return c.getId();
            } catch (Exception e) {
                try { utx.rollback(); } catch (Exception ignored) { /* rollback best-effort: original exception propagates */ }
                throw new RuntimeException(e);
            }
        });
    }

    private void execSql(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private long scalar(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1L;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
