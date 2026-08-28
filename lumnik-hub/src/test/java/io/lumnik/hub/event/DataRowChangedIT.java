package io.lumnik.hub.event;

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
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.lumnik.hub.testsupport.EventItSupport.execSql;
import static io.lumnik.hub.testsupport.EventItSupport.purgeWebhookEndpoints;
import static io.lumnik.hub.testsupport.EventItSupport.runInTxAsSystem;
import static io.lumnik.hub.testsupport.EventItSupport.seedWebhookEndpoint;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * É2.b Task 3 — {@code data.row.changed} born at the write path, subscription-gated.
 *
 * <p>Harness modelled on {@code RunCompletedPayloadIT}: a real {@code csv-file} connector runs
 * end-to-end into {@code connector.t_drc}; per-row events are read from {@code core.event_outbox}.
 * The outbox is global (no tenant session here), so {@code @BeforeEach} wipes the foreign
 * {@code data.row.changed} rows and the test's own webhook endpoints via the admin datasource.
 */
@QuarkusTest
class DataRowChangedIT {

    private static final String TABLE_FQN = "connector.t_drc";

    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject UserTransaction utx;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    private final ObjectMapper json = new ObjectMapper();
    private Path csvFile;

    @BeforeEach
    void setup() throws Exception {
        csvFile = Files.createTempFile("data_row_changed_it_", ".csv");
        csvFile.toFile().deleteOnExit();
        Files.writeString(csvFile, "row_id,email\n1,a@x\n2,b@x\n3,c@x\n");

        execSql(adminDs, "DROP TABLE IF EXISTS connector.t_drc");
        // The registry is a DB table keyed by (tenant, mapping); drop it so each test re-creates
        // the dynamic table from scratch (a stale registry row would skip createSchema → "relation
        // does not exist" on the next run).
        execSql(adminDs, "DELETE FROM connector.dynamic_table WHERE table_name = 't_drc'");
        execSql(adminDs, "DELETE FROM core.event_outbox WHERE event_type = 'data.row.changed'");
        // Cleanups run on the admin datasource (BYPASSRLS in prod, table owner in tests), so no
        // tenant GUC is needed. A SET LOCAL here would be a no-op anyway (separate autocommit
        // connection per execSql, outside a tx block).
        // One transaction with the endpoints locked first — WebhookConsumer ticks every 5 s and a
        // delivery landing mid-delete breaks the teardown (the 2026-07-24 nightly).
        purgeWebhookEndpoints(adminDs, "drc-%");
    }

    @Test
    void subscribedRunEmitsPerNewRow() throws Exception {
        seedWebhookEndpoint(adminDs, utx, "drc-sub", "data.row.changed");
        UUID connectorId = persistCsvConnector(csvFile.toAbsolutePath().toString());

        RunStatus s = engine.runSync(connectorId, new RunTrigger.Manual(1L));
        assertThat(s).isInstanceOf(RunStatus.Completed.class);

        List<Row> events = dataRowChangedEvents();
        assertThat(events).as("exactly 3 data.row.changed events").hasSize(3);

        Set<String> rowHashes = rowHashesInTable();
        Set<String> entityKeys = new HashSet<>();
        for (Row e : events) {
            assertThat(e.entity).as("entity = target table fqn").isEqualTo(TABLE_FQN);
            assertThat(e.source).as("source = connector id").isEqualTo(connectorId.toString());
            JsonNode p = json.readTree(e.payload);
            assertThat(p.get("op").asText()).isEqualTo("insert");
            assertThat(p.has("run_id")).as("payload carries run_id").isTrue();
            assertThat(p.get("run_id").asText()).isNotBlank();
            assertThat(p.get("entity").asText()).isEqualTo(TABLE_FQN);
            assertThat(p.get("entity_key").asText()).isEqualTo(e.entityKey);
            assertThat(rowHashes).as("entity_key is a _row_hash present in the table").contains(e.entityKey);
            entityKeys.add(e.entityKey);
        }
        assertThat(entityKeys).as("3 distinct entity_keys").hasSize(3);
    }

    @Test
    void unsubscribedRunEmitsNothing() throws Exception {
        UUID connectorId = persistCsvConnector(csvFile.toAbsolutePath().toString());

        RunStatus s = engine.runSync(connectorId, new RunTrigger.Manual(1L));
        assertThat(s).isInstanceOf(RunStatus.Completed.class);

        assertThat(dataRowChangedEvents()).as("no subscription → zero events").isEmpty();
    }

    @Test
    void reIngestEmitsNothingNew() throws Exception {
        seedWebhookEndpoint(adminDs, utx, "drc-reingest", "data.row.changed");
        UUID connectorId = persistCsvConnector(csvFile.toAbsolutePath().toString());

        assertThat(engine.runSync(connectorId, new RunTrigger.Manual(1L)))
            .isInstanceOf(RunStatus.Completed.class);
        assertThat(engine.runSync(connectorId, new RunTrigger.Manual(1L)))
            .isInstanceOf(RunStatus.Completed.class);

        assertThat(dataRowChangedEvents())
            .as("re-ingesting identical content is event-silent → still 3 total")
            .hasSize(3);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private record Row(String entity, String entityKey, String source, String payload) {}

    private UUID persistCsvConnector(String filePath) {
        return runInTxAsSystem(utx, () -> {
            Connector c = new Connector();
            c.setTenantId(1L);
            c.setName("data-row-changed-" + System.nanoTime());
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
            ep.setConfig(Map.of("name", "drc"));
            endpoints.persist(ep);
            endpoints.flush();

            return c.getId();
        });
    }

    private List<Row> dataRowChangedEvents() {
        List<Row> out = new ArrayList<>();
        try (Connection c = adminDs.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "select entity, entity_key, source, payload::text from core.event_outbox "
                 + "where event_type = 'data.row.changed' order by created_at")) {
            while (rs.next()) {
                out.add(new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
            }
        } catch (Exception e) { throw new RuntimeException(e); }
        return out;
    }

    private Set<String> rowHashesInTable() {
        Set<String> out = new HashSet<>();
        try (Connection c = adminDs.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select _row_hash from connector.t_drc")) {
            while (rs.next()) out.add(rs.getString(1));
        } catch (Exception e) { throw new RuntimeException(e); }
        return out;
    }
}
