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
 * Proves the file-fatal tokenizer-desync contract:
 * when one file in a glob has an unterminated quote (parser desync), that file emits a single
 * file-fatal DLQ entry but sibling files are still processed to completion.
 */
@QuarkusTest
class CsvFileFatalDlqIT {

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
        execSql("DROP TABLE IF EXISTS connector.t_fatal_glob");
    }

    @Test
    void unterminated_quote_is_file_fatal_but_sibling_file_is_processed() throws Exception {
        Path dir = Files.createTempDirectory("fatal_glob_");
        dir.toFile().deleteOnExit();
        // File A: clean row, then an unterminated quote → desync.
        Files.writeString(dir.resolve("a.csv"), "row_id,email\n1,a@x.com\n\"2,unterminated,x\n");
        // File B: fully clean.
        Files.writeString(dir.resolve("b.csv"), "row_id,email\n9,z@x.com\n");

        UUID connectorId = persistCsvConnector(
            "fatal-glob",
            Map.of("kind", "local", "path", dir.toAbsolutePath() + "/*.csv"),
            Map.of("has_header", true),
            "fatal_glob");

        RunStatus status = engine.runSync(connectorId, new RunTrigger.Manual(1L));

        assertThat(status).as("run must not hard-fail").isNotInstanceOf(RunStatus.Failed.class);
        // A's clean row (1) + B's clean row (9) = 2 good rows.
        assertThat(scalar("SELECT count(*) FROM connector.t_fatal_glob")).isEqualTo(2L);
        var pending = TenantContext.callAsSystem(() -> dlqRepo.findPendingByConnector(connectorId, 10));
        assertThat(pending).as("file-fatal desync must produce a DLQ entry").isNotEmpty();
        assertThat(pending.get(0).getSkipReason()).isEqualTo("MalformedSource");
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
