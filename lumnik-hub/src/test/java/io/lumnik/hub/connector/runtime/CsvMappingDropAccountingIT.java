package io.lumnik.hub.connector.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorDeadLetter;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.connector.repository.ConnectorDeadLetterRepository;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
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
 * End-to-end IT: proves the conservation invariant ({@code records_in = records_out +
 * records_skipped}) holds HONESTLY through the mapping tier — the defect this branch fixes
 * (design: {@code docs/superpowers/specs/2026-08-20-mapping-drop-accounting-design.md}).
 *
 * <p>Three scenarios, each its own connector + table (auto-schema per table name):
 * <ul>
 *   <li>{@code validate-row} drops a row with a blank required column → the run counts the
 *       drop AND the row reaches the DLQ ({@code ValidationFailed}, payload = the dropped row).</li>
 *   <li>{@code filter-where} drops a row that doesn't match the declared policy → the run counts
 *       the drop but the DLQ stays empty (declared filtering is not noise).</li>
 *   <li>{@code split-row} fans one source row into two target rows, with no drops → conservation
 *       counts SOURCE rows ({@code records_out} stays 3), while the target table holds 4 rows.</li>
 * </ul>
 */
@QuarkusTest
class CsvMappingDropAccountingIT {

    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject ConnectorRunRepository runs;
    @Inject ConnectorDeadLetterRepository dlqRepo;
    @Inject UserTransaction utx;

    @Inject
    @io.quarkus.agroal.DataSource("admin")
    AgroalDataSource adminDs;

    // The registry (connector.dynamic_table) must be cleared alongside the DROP TABLE — mirrors
    // BenignSkipNoDlqIT. DynamicTableWriter.write() skips createTable when a registry row for the
    // mapping id already exists, so under DevServices container reuse a leftover registry row would
    // point a fresh run at a table that was just dropped (createTable never fires to recreate it).
    @BeforeEach
    void dropTables() {
        execSql("DROP TABLE IF EXISTS connector.t_drop_acct_validate");
        execSql("DROP TABLE IF EXISTS connector.t_drop_acct_filter");
        execSql("DROP TABLE IF EXISTS connector.t_drop_acct_split");
        execSql("DELETE FROM connector.dynamic_table WHERE table_name = 't_drop_acct_validate'");
        execSql("DELETE FROM connector.dynamic_table WHERE table_name = 't_drop_acct_filter'");
        execSql("DELETE FROM connector.dynamic_table WHERE table_name = 't_drop_acct_split'");
    }

    @Test
    void validateRow_drops_one_row_counted_and_dlq_captures_it() throws Exception {
        Path csv = Files.createTempFile("drop_acct_validate_", ".csv");
        csv.toFile().deleteOnExit();
        // Row 2 has a blank "name" — required by validate-row.
        Files.writeString(csv, "row_id,name\n1,Alice\n2,\n3,Carol\n");

        Map<String, Object> mapping = Map.of(
            "row", List.of(Map.of("validate-row", Map.of("required", List.of("name")))));

        UUID connectorId = persistCsvConnector(
            "drop-acct-validate", csv.toAbsolutePath().toString(), mapping, "drop_acct_validate");

        RunStatus status = engine.runSync(connectorId, new RunTrigger.Manual(1L));
        assertThat(status).as("run must reach a terminal status, not Failed")
            .isNotInstanceOf(RunStatus.Failed.class);

        ConnectorRun run = latestRun(connectorId);
        assertThat(run.getRecordsIn()).as("all 3 source rows were read").isEqualTo(3L);
        assertThat(run.getRecordsOut()).as("2 rows survive validate-row").isEqualTo(2L);
        assertThat(run.getRecordsSkipped()).as("1 row dropped by validate-row").isEqualTo(1L);

        assertThat(scalar("SELECT count(*) FROM connector.t_drop_acct_validate"))
            .as("the 2 valid rows must be persisted")
            .isEqualTo(2L);

        List<ConnectorDeadLetter> dlq = TenantContext.callAsSystem(
            () -> dlqRepo.findByRun(run.getId(), 10));
        assertThat(dlq).as("exactly 1 DLQ entry for the validation-dropped row").hasSize(1);
        ConnectorDeadLetter entry = dlq.get(0);
        assertThat(entry.getSkipReason()).isEqualTo("ValidationFailed");
        JsonNode payload = entry.getPayload();
        assertThat(payload.path("row_id").asText()).as("payload is the dropped row").isEqualTo("2");
        assertThat(payload.path("name").isNull()).as("payload's name is the blank value").isTrue();
    }

    @Test
    void filterWhere_drops_one_row_counted_but_dlq_stays_empty() throws Exception {
        Path csv = Files.createTempFile("drop_acct_filter_", ".csv");
        csv.toFile().deleteOnExit();
        // Row 2 is INACTIVE — excluded by the declared filter-where policy.
        Files.writeString(csv, "row_id,name,status\n1,Alice,ACTIVE\n2,Bob,INACTIVE\n3,Carol,ACTIVE\n");

        Map<String, Object> mapping = Map.of(
            "dataset", List.of(Map.of("filter-where", Map.of("column", "status", "equals", "ACTIVE"))));

        UUID connectorId = persistCsvConnector(
            "drop-acct-filter", csv.toAbsolutePath().toString(), mapping, "drop_acct_filter");

        RunStatus status = engine.runSync(connectorId, new RunTrigger.Manual(1L));
        assertThat(status).as("run must reach a terminal status, not Failed")
            .isNotInstanceOf(RunStatus.Failed.class);

        ConnectorRun run = latestRun(connectorId);
        assertThat(run.getRecordsIn()).as("all 3 source rows were read").isEqualTo(3L);
        assertThat(run.getRecordsOut()).as("2 rows survive filter-where").isEqualTo(2L);
        assertThat(run.getRecordsSkipped()).as("1 row excluded by filter-where").isEqualTo(1L);

        assertThat(scalar("SELECT count(*) FROM connector.t_drop_acct_filter"))
            .as("the 2 ACTIVE rows must be persisted")
            .isEqualTo(2L);

        List<ConnectorDeadLetter> dlq = TenantContext.callAsSystem(
            () -> dlqRepo.findByRun(run.getId(), 10));
        assertThat(dlq).as("declared filtering is not noise — no DLQ entry").isEmpty();
    }

    @Test
    void splitRow_fanOut_countsSourceRows_targetTableHoldsFragments() throws Exception {
        Path csv = Files.createTempFile("drop_acct_split_", ".csv");
        csv.toFile().deleteOnExit();
        // Row 2's "tags" fans out into 2 fragments; rows 1 and 3 have no tags (1:1).
        Files.writeString(csv, "row_id,name,tags\n1,Alice,\n2,Bob,x|y\n3,Carol,\n");

        Map<String, Object> mapping = Map.of(
            "row", List.of(Map.of("split-row", Map.of("by", "tags", "sep", "|"))));

        UUID connectorId = persistCsvConnector(
            "drop-acct-split", csv.toAbsolutePath().toString(), mapping, "drop_acct_split");

        RunStatus status = engine.runSync(connectorId, new RunTrigger.Manual(1L));
        assertThat(status).as("run must reach a terminal status, not Failed")
            .isNotInstanceOf(RunStatus.Failed.class);

        ConnectorRun run = latestRun(connectorId);
        assertThat(run.getRecordsIn()).as("all 3 source rows were read").isEqualTo(3L);
        assertThat(run.getRecordsOut())
            .as("conservation counts SOURCE rows — split-row fan-out is not a drop")
            .isEqualTo(3L);
        assertThat(run.getRecordsSkipped()).as("no drops in this scenario").isEqualTo(0L);

        assertThat(scalar("SELECT count(*) FROM connector.t_drop_acct_split"))
            .as("the target table holds 4 rows: 1 (row1) + 2 (row2 fan-out) + 1 (row3)")
            .isEqualTo(4L);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private ConnectorRun latestRun(UUID connectorId) {
        return TenantContext.callAsSystem(() ->
            runs.findLatestByConnector(connectorId)
                .orElseThrow(() -> new AssertionError("expected a run for connector " + connectorId)));
    }

    private UUID persistCsvConnector(
        String connectorName, String filePath, Map<String, Object> mapping, String table
    ) {
        return TenantContext.callAsSystem(() -> {
            try {
                utx.begin();

                Map<String, Object> cfg = new HashMap<>();
                cfg.put("kind", "csv-file");
                cfg.put("transport", Map.of("kind", "local", "path", filePath));
                cfg.put("parser", Map.of("has_header", true));
                cfg.put("mapping", mapping);

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
                ep.setPath(filePath);
                ep.setEnabled(true);
                ep.setConfig(Map.of("name", table));
                endpoints.persist(ep);
                endpoints.flush();

                utx.commit();
                return c.getId();
            } catch (Exception e) {
                try { utx.rollback(); } catch (Exception rb) { e.addSuppressed(rb); }
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
