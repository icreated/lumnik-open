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
 * End-to-end IT: proves that a CSV connector with a manifest {@code mapping.cell} Kotlin hook
 * transforms column values during ingestion.
 *
 * <p>The {@code uppercase-phone.kts} script uppercases the {@code phone} column.
 * The fixture CSV has lowercase phone values; after ingestion they must be UPPERCASE in the hub
 * table, proving the hook ran through the full connector → MappingEngine path.
 */
@QuarkusTest
class CsvKotlinHookIT {

    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject UserTransaction utx;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    private Path csvFile;

    @BeforeEach
    void setup() throws Exception {
        csvFile = Files.createTempFile("csv_hook_it_", ".csv");
        csvFile.toFile().deleteOnExit();
        // Avoid column name "id" — it collides with DynamicTableWriter's BIGSERIAL system column.
        Files.writeString(csvFile, "row_id,phone\n1,abc\n2,xyz\n");

        execSql("DROP TABLE IF EXISTS connector.t_csv_hook");
    }

    @Test
    void kotlinCellHook_uppercasesPhone_endToEnd() throws Exception {
        String filePath = csvFile.toAbsolutePath().toString();

        // mapping block: a cell hook that runs uppercase-phone.kts
        Map<String, Object> mappingBlock = Map.of(
            "cell", List.of(
                Map.of("kotlin", Map.of("script", "classpath:mapping/uppercase-phone.kts"))
            )
        );

        UUID connectorId = TenantContext.callAsSystem(() -> {
            try {
                utx.begin();

                Map<String, Object> cfg = new HashMap<>();
                cfg.put("kind", "csv-file");
                cfg.put("transport", Map.of("kind", "local", "path", filePath));
                cfg.put("parser",    Map.of("has_header", true));
                cfg.put("mapping",   mappingBlock);

                Connector c = new Connector();
                c.setTenantId(1L);
                c.setName("csv-hook-" + System.nanoTime());
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
                ep.setConfig(Map.of("name", "csv_hook"));
                endpoints.persist(ep);
                endpoints.flush();

                utx.commit();
                return c.getId();
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        RunStatus status = engine.runSync(connectorId, new RunTrigger.Manual(1L));

        assertThat(status)
            .as("connector run must complete successfully")
            .isInstanceOf(RunStatus.Completed.class);

        assertThat(scalar("SELECT count(*) FROM connector.t_csv_hook"))
            .as("both CSV rows must be persisted")
            .isEqualTo(2L);

        // Core assertion: the Kotlin hook must have uppercased the phone values
        assertThat(scalarText("SELECT phone FROM connector.t_csv_hook WHERE row_id = '1'"))
            .as("phone for row 1 must be uppercased by the Kotlin cell hook")
            .isEqualTo("ABC");

        assertThat(scalarText("SELECT phone FROM connector.t_csv_hook WHERE row_id = '2'"))
            .as("phone for row 2 must be uppercased by the Kotlin cell hook")
            .isEqualTo("XYZ");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private void execSql(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private long scalar(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1L;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private String scalarText(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
