package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #888: every cell hook runs ONCE per value, through the real connector path. {@code ChunkExecutor}
 * calls {@code writer.prepare(records)} and then {@code writer.write(records)} on the same row maps;
 * the mapping writer used to transform them in both, so a hook that REWRITES a value (rather than
 * nulling it) was applied twice and the row landed wrong — with nothing counted and nothing logged.
 * None of the three specs below has a RAG target, which is every csv-file and jdbc-generic spec
 * shipped today.
 */
@QuarkusTest
class CsvCellHooksRunOnceIT {

    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    @Test
    void a_rewriting_hook_is_applied_once() throws Exception {
        UUID connectorId = run("csv_once_rewrite", "row_id,code\n1,42\n",
            Map.of("regex-replace", Map.of("column", "code", "pattern", "^", "replacement", "X-")));

        assertThat(scalarText("SELECT code FROM connector.t_csv_once_rewrite WHERE row_id = '1'"))
            .as("regex-replace '^' -> 'X-' prefixes once, it does not prefix the prefix")
            .isEqualTo("X-42");
        assertThat(cellErrors(connectorId)).isZero();
    }

    @Test
    void split_into_array_lands_the_parts_not_its_own_list_split_again() throws Exception {
        run("csv_once_split", "row_id,tags\n1,A/B\n",
            Map.of("split", Map.of("column", "tags", "by", "/", "into-array", true)));

        assertThat(scalarText("SELECT tags::text FROM connector.t_csv_once_split WHERE row_id = '1'"))
            .as("a second split would run on the list's toString() and land [\"[A, B]\"]")
            .isEqualTo("[\"A\", \"B\"]");
    }

    @Test
    void a_counted_shortfall_is_counted_once() throws Exception {
        // Three parts for two declared names: split writes the row and counts ONE cell error.
        UUID connectorId = run("csv_once_count", "row_id,pair\n1,x/y/z\n",
            Map.of("split", Map.of("column", "pair", "by", "/", "into", List.of("p1", "p2"))));

        assertThat(scalarText("SELECT p1 || ',' || p2 FROM connector.t_csv_once_count WHERE row_id = '1'"))
            .isEqualTo("x,y");
        assertThat(cellErrors(connectorId))
            .as("one value, one shortfall — not one per transform pass")
            .isEqualTo(1L);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Writes {@code csv} to a temp file, applies a csv-file connector whose mapping has the one
     *  {@code cellHook}, runs it to completion into {@code connector.t_<table>}, returns its id. */
    private UUID run(String table, String csv, Map<String, Object> cellHook) throws Exception {
        execSql("DROP TABLE IF EXISTS connector.t_" + table);
        Path csvFile = Files.createTempFile("csv_once_it_", ".csv");
        csvFile.toFile().deleteOnExit();
        // Avoid column name "id" — it collides with DynamicTableWriter's BIGSERIAL system column.
        Files.writeString(csvFile, csv);
        String filePath = csvFile.toAbsolutePath().toString();

        // requiringNew() rolls back on any exception, so a failed seed never leaves a transaction on
        // the thread for the next test to trip over.
        UUID connectorId = TenantContext.callAsSystem(() -> QuarkusTransaction.requiringNew().call(() -> {
            Map<String, Object> cfg = new HashMap<>();
            cfg.put("kind", "csv-file");
            cfg.put("transport", Map.of("kind", "local", "path", filePath));
            // Pinned: the detector also weighs '|' and ';', and a value must never be read as one.
            cfg.put("parser",    Map.of("has_header", true, "delimiter", ","));
            cfg.put("mapping",   Map.of("cell", List.of(cellHook)));

            Connector c = new Connector();
            c.setTenantId(1L);
            c.setName("csv-once-" + System.nanoTime());
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

            return c.getId();
        }));

        RunStatus status = engine.runSync(connectorId, new RunTrigger.Manual(1L));
        assertThat(status).as("connector run must complete").isInstanceOf(RunStatus.Completed.class);
        return connectorId;
    }

    private long cellErrors(UUID connectorId) {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT cell_errors FROM connector.connector_run WHERE connector_id = ?")) {
            ps.setObject(1, connectorId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("one run row for the connector").isTrue();
                return rs.getLong(1);
            }
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void execSql(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private String scalarText(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
