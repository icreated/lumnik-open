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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1863: a {@code key;value} block before the table is read, counted in SKIPPED and dead-lettered nowhere, while a
 * truncated line after the table began still goes to the dead letters. Whole run through the engine.
 */
@QuarkusTest
class CsvLeadingShortRowsIT {

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
        execSql("DROP TABLE IF EXISTS connector.t_leading_short");
    }

    @Test
    void the_block_is_skipped_not_dead_lettered_and_a_later_truncated_line_is() throws Exception {
        Path dir = Files.createTempDirectory("leading_short_");
        dir.toFile().deleteOnExit();
        Files.writeString(dir.resolve("export.csv"), String.join("\n",
            "BATCH;B-42", "INSTRUMENT;QTOF", "METHOD;QC", "CALIBRATED;2026-11-01", "OPERATOR;ab",
            "1;a;b", "2;a;b", "3;a;b", "4;a", "5;a;b", ""));

        UUID connectorId = persistCsvConnector(dir, Map.of("delimiter", ";", "has_header", false,
            "columns", List.of("sample", "x", "y"), "short_rows", "skip-leading"));

        RunStatus status = engine.runSync(connectorId, new RunTrigger.Manual(1L));

        assertThat(status).isInstanceOf(RunStatus.Completed.class);
        assertThat(scalar("SELECT count(*) FROM connector.t_leading_short")).isEqualTo(4L);
        var pending = TenantContext.callAsSystem(() -> dlqRepo.findPendingByConnector(connectorId, 20));
        assertThat(pending).as("only the truncated data line is quarantined").hasSize(1);
        assertThat(pending.get(0).getSkipReason()).isEqualTo("MalformedSource");
        assertThat(scalar("SELECT records_in FROM connector.connector_run WHERE connector_id = '" + connectorId + "'"))
            .isEqualTo(10L);
        assertThat(scalar("SELECT records_out FROM connector.connector_run WHERE connector_id = '" + connectorId + "'"))
            .isEqualTo(4L);
        assertThat(scalar("SELECT records_skipped FROM connector.connector_run WHERE connector_id = '" + connectorId + "'"))
            .as("the five block lines and the truncated one: in = out + skipped").isEqualTo(6L);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private UUID persistCsvConnector(Path dir, Map<String, Object> parser) {
        return TenantContext.callAsSystem(() -> {
            try {
                utx.begin();
                Map<String, Object> transport = Map.of("kind", "local", "path", dir.toAbsolutePath() + "/*.csv");
                Map<String, Object> cfg = new HashMap<>();
                cfg.put("kind", "csv-file");
                cfg.put("transport", transport);
                cfg.put("parser", parser);

                Connector c = new Connector();
                c.setTenantId(1L);
                c.setName("leading-short-" + System.nanoTime());
                c.setTypeId("csv-file");
                c.setConfig(cfg);
                connectors.persist(c);
                connectors.flush();

                ConnectorEndpoint ep = new ConnectorEndpoint();
                ep.setTenantId(1L);
                ep.setConnectorId(c.getId());
                ep.setName("default");
                ep.setMethod("GET");
                ep.setPath(transport.get("path").toString());
                ep.setEnabled(true);
                ep.setConfig(Map.of("name", "leading_short"));
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
