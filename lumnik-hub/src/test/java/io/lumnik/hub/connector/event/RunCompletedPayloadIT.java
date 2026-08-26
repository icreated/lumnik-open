package io.lumnik.hub.connector.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.runtime.ConnectorEngine;
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
import java.util.Map;
import java.util.UUID;

import static io.lumnik.hub.testsupport.EventItSupport.execSql;
import static io.lumnik.hub.testsupport.EventItSupport.runInTxAsSystem;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the enriched {@code connector.run.completed} payload (É2.b Task 1): an {@code endpoint_id}
 * and a {@code tables} object carrying per-table {@code {written, skipped}} counts.
 *
 * <p>Harness modelled on {@code CsvConnectorWritesIT}: a real {@code csv-file} connector runs
 * end-to-end into {@code connector.t_csv_writes}; the completed event is read from
 * {@code core.event_outbox}.
 */
@QuarkusTest
class RunCompletedPayloadIT {

    private static final String TABLE_FQN = "connector.t_csv_writes";

    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject UserTransaction utx;

    @Inject
    @io.quarkus.agroal.DataSource("admin")
    AgroalDataSource adminDs;

    private final ObjectMapper json = new ObjectMapper();
    private Path csvFile;
    private UUID endpointId;

    @BeforeEach
    void setup() throws Exception {
        csvFile = Files.createTempFile("run_completed_payload_it_", ".csv");
        csvFile.toFile().deleteOnExit();
        Files.writeString(csvFile, "row_id,email\n1,a@x\n2,b@x\n3,c@x\n");

        execSql(adminDs, "DROP TABLE IF EXISTS connector.t_csv_writes");
        execSql(adminDs, "DELETE FROM core.event_outbox WHERE event_type = 'connector.run.completed'");
    }

    @Test
    void completedPayload_carriesEndpointId_andPerTableCounts() throws Exception {
        UUID connectorId = persistCsvConnector(csvFile.toAbsolutePath().toString());

        // First run: 3 fresh rows → written: 3, skipped: 0
        RunStatus s1 = engine.runSync(connectorId, new RunTrigger.Manual(1L));
        assertThat(s1).isInstanceOf(RunStatus.Completed.class);

        JsonNode payload1 = latestCompletedPayload();
        assertThat(payload1.has("endpoint_id")).as("payload has endpoint_id").isTrue();
        assertThat(payload1.get("endpoint_id").asText()).isEqualTo(endpointId.toString());

        JsonNode tables1 = payload1.get("tables");
        assertThat(tables1).as("payload has a tables object").isNotNull();
        JsonNode t1 = tables1.get(TABLE_FQN);
        assertThat(t1).as("tables has the target fqn key").isNotNull();
        assertThat(t1.get("written").asLong()).isEqualTo(3L);
        assertThat(t1.get("skipped").asLong()).isEqualTo(0L);

        // Re-run the SAME file: identical content → all rows conflict (SKIP) → written: 0, skipped: 3
        RunStatus s2 = engine.runSync(connectorId, new RunTrigger.Manual(1L));
        assertThat(s2).isInstanceOf(RunStatus.Completed.class);

        JsonNode payload2 = latestCompletedPayload();
        JsonNode t2 = payload2.get("tables").get(TABLE_FQN);
        assertThat(t2).as("second run still records the table").isNotNull();
        assertThat(t2.get("written").asLong()).isEqualTo(0L);
        assertThat(t2.get("skipped").asLong()).isEqualTo(3L);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private UUID persistCsvConnector(String filePath) {
        return runInTxAsSystem(utx, () -> {
            Connector c = new Connector();
            c.setTenantId(1L);
            c.setName("run-completed-payload-" + System.nanoTime());
            c.setTypeId("csv-file");
            c.setConfig(Map.of(
                "kind", "csv-file",
                "transport", Map.of("kind", "local", "path", filePath),
                "parser",    Map.of("has_header", true)
            ));
            connectors.persist(c);
            connectors.flush();

            ConnectorEndpoint ep = new ConnectorEndpoint();
            ep.setTenantId(1L);
            ep.setConnectorId(c.getId());
            ep.setName("default");
            ep.setMethod("GET");
            ep.setPath(filePath);
            ep.setEnabled(true);
            ep.setConfig(Map.of("name", "csv_writes"));
            endpoints.persist(ep);
            endpoints.flush();
            endpointId = ep.getId();

            return c.getId();
        });
    }

    private JsonNode latestCompletedPayload() {
        try (Connection c = adminDs.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "select payload::text from core.event_outbox "
                 + "where event_type = 'connector.run.completed' order by created_at desc limit 1")) {
            if (!rs.next()) throw new IllegalStateException("no connector.run.completed event found");
            return json.readTree(rs.getString(1));
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
