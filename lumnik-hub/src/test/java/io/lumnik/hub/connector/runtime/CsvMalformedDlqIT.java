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
 * Acceptance test for read-time malformed-row isolation: a CSV file with a malformed row (fewer
 * fields than the header) must dead-letter the bad row while still ingesting the good rows, instead
 * of aborting the whole run. Asserts:
 * <ul>
 *   <li>the bad row is captured in {@code connector.connector_dead_letter},</li>
 *   <li>the good rows are persisted to {@code connector.t_tort_dlq},</li>
 *   <li>the run reaches a terminal status (not {@code Failed}).</li>
 * </ul>
 */
@QuarkusTest
class CsvMalformedDlqIT {

    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject ConnectorDeadLetterRepository dlqRepo;
    @Inject UserTransaction utx;

    @Inject
    @io.quarkus.agroal.DataSource("admin")
    AgroalDataSource adminDs;

    @BeforeEach
    void dropTable() {
        execSql("DROP TABLE IF EXISTS connector.t_tort_dlq");
    }

    /**
     * CSV with one malformed middle row: good rows must land, bad row must reach DLQ.
     *
     * <p>Input: {@code row_id,email\n1,a@x.com\nBROKEN_ROW_ONE_FIELD\n3,c@x.com\n}
     * The second data line has only 1 field under a 2-column header.
     */
    @Test
    void malformed_row_routed_to_dlq_and_good_rows_persisted() throws Exception {
        Path csv = Files.createTempFile("tort_dlq_", ".csv");
        csv.toFile().deleteOnExit();
        Files.writeString(csv, "row_id,email\n1,a@x.com\nBROKEN_ROW_ONE_FIELD\n3,c@x.com\n");

        UUID connectorId = persistCsvConnector(
            "tort-dlq",
            Map.of("kind", "local", "path", csv.toAbsolutePath().toString()),
            Map.of("has_header", true),
            "tort_dlq"
        );

        RunStatus status = engine.runSync(connectorId, new RunTrigger.Manual(1L));

        // (c) run must complete with a terminal status — not a hard failure
        assertThat(status)
            .as("run must reach a terminal status (Completed or Partial), not Failed")
            .isNotInstanceOf(RunStatus.Failed.class);

        // (b) the two good rows must be persisted
        assertThat(scalar("SELECT count(*) FROM connector.t_tort_dlq"))
            .as("the 2 good rows must be persisted to connector.t_tort_dlq")
            .isEqualTo(2L);

        // (a) the malformed row must be captured in the dead-letter table
        var pending = TenantContext.callAsSystem(() ->
            dlqRepo.findPendingByConnector(connectorId, 10));
        assertThat(pending)
            .as("the malformed row must produce at least one DLQ entry")
            .isNotEmpty();
        assertThat(pending.get(0).getSkipReason())
            .as("skipReason must be non-blank")
            .isNotBlank();

        // (d) conservation: recordsIn must account for EVERY source row read, including
        // the malformed one that was dead-lettered. A row you dead-letter is a row you
        // read, so recordsIn == recordsOut + recordsSkipped (3 = 2 good + 1 malformed).
        long recordsIn = scalar("SELECT records_in FROM connector.connector_run WHERE connector_id = '" + connectorId + "'");
        long recordsOut = scalar("SELECT records_out FROM connector.connector_run WHERE connector_id = '" + connectorId + "'");
        long recordsSkipped = scalar("SELECT records_skipped FROM connector.connector_run WHERE connector_id = '" + connectorId + "'");
        assertThat(recordsIn)
            .as("recordsIn must include the malformed (read-but-rejected) row: in == out + skipped")
            .isEqualTo(recordsOut + recordsSkipped);
        assertThat(recordsIn)
            .as("all 3 source rows were read (2 good + 1 malformed)")
            .isEqualTo(3L);
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
