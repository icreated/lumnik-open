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
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.OffsetDateTime;
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
    /** The duration test writes to its OWN table: DynamicTableRegistry caches "this table exists",
     *  so a second test reusing a physically dropped name would skip the DDL and insert into nothing. */
    private static final String DURATION_TARGET = "csv_duration";

    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject UserTransaction utx;

    @Inject
    @DataSource("admin")
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
        execSql(adminDs, "DROP TABLE IF EXISTS connector.t_" + DURATION_TARGET);
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


    /**
     * ONE source of duration. The completed event's {@code duration_ms} must be the run's own
     * JOURNAL span — {@code ended_at − started_at} on {@code connector.connector_run} — which is
     * the figure every other reader already derives: {@code lm run list} and the TUI's DURATION
     * column read the stored columns, and the end-of-run log line in {@code finalizeRun} uses those
     * same two instants a moment before the database rounds {@code ended_at}.
     *
     * <p>A second, independent measurement (a {@code nanoTime} span taken over the chunk loop)
     * necessarily reports a DIFFERENT, shorter number: it starts after the run row was created,
     * after the endpoint and connector were resolved and after the advisory lock was taken. Two
     * numbers for one run is one number too many — whichever a human reads, the other contradicts it.
     */
    @Test
    void completedPayload_durationIsTheJournalSpan() throws Exception {
        UUID connectorId = persistCsvConnector(csvFile.toAbsolutePath().toString(), DURATION_TARGET);

        RunStatus s = engine.runSync(connectorId, new RunTrigger.Manual(1L));
        assertThat(s).isInstanceOf(RunStatus.Completed.class);

        JsonNode payload = latestCompletedPayload();
        UUID runId = UUID.fromString(payload.get("run_id").asText());

        assertThat(payload.get("duration_ms").asLong())
            .as("the event must carry the run's journal span, not a second measurement of its own")
            .isEqualTo(journalSpanMs(runId));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private UUID persistCsvConnector(String filePath) {
        return persistCsvConnector(filePath, "csv_writes");
    }

    private UUID persistCsvConnector(String filePath, String targetName) {
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
            ep.setConfig(Map.of("name", targetName));
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

    /** The ledger's own span for a run, in the same truncation as {@code Duration.toMillis()}. */
    private long journalSpanMs(UUID runId) {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "select started_at, ended_at from connector.connector_run where id = ?")) {
            ps.setObject(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("run row not found: " + runId);
                return Duration.between(
                        rs.getObject(1, OffsetDateTime.class).toInstant(),
                        rs.getObject(2, OffsetDateTime.class).toInstant()).toMillis();
            }
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
