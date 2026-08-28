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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT: verifies that a CSV connector with a declared {@code indexes} key creates the expected
 * Postgres index on the hub table, and that a second run keeps exactly one index (idempotence).
 */
@QuarkusTest
class CsvDeclaredIndexesIT {

    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject UserTransaction utx;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    @BeforeEach
    void cleanUp() {
        execSql("DROP TABLE IF EXISTS connector.t_cidx_idx");
    }

    @Test
    void declared_index_is_created_with_the_table() throws Exception {
        Path csv = Files.createTempFile("cidx_idx_", ".csv");
        csv.toFile().deleteOnExit();
        Files.writeString(csv, "row_id,email\n1,a@x\n2,b@x\n", StandardCharsets.UTF_8);

        UUID id = TenantContext.callAsSystem(() -> {
            try {
                utx.begin();
                var c = new Connector();
                c.setTenantId(1L);
                c.setName("cidx-idx-" + System.nanoTime());
                c.setTypeId("csv-file");
                c.setConfig(Map.of("kind", "csv-file",
                    "transport", Map.of("kind", "local", "path", csv.toAbsolutePath().toString()),
                    "parser", Map.of("has_header", true),
                    "indexes", List.of(Map.of("columns", List.of("email")))));
                connectors.persist(c);
                connectors.flush();
                var ep = new ConnectorEndpoint();
                ep.setConnectorId(c.getId());
                ep.setTenantId(1L);
                ep.setName("default");
                ep.setMethod("GET");
                ep.setPath(csv.toAbsolutePath().toString());
                ep.setEnabled(true);
                ep.setConfig(Map.of("name", "cidx_idx"));
                endpoints.persist(ep);
                utx.commit();
                return c.getId();
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        assertThat(engine.runSync(id, new RunTrigger.Manual(1L)))
            .isInstanceOf(RunStatus.Completed.class);

        // index named per DynamicTableWriter.createIndexes: <table>_idx_<cols> → t_cidx_idx_idx_email
        assertThat(scalar("SELECT count(*) FROM pg_indexes WHERE schemaname='connector' "
            + "AND tablename='t_cidx_idx' AND indexname='t_cidx_idx_idx_email'")).isEqualTo(1L);

        // idempotent re-run keeps exactly one
        engine.runSync(id, new RunTrigger.Manual(1L));
        assertThat(scalar("SELECT count(*) FROM pg_indexes WHERE schemaname='connector' "
            + "AND tablename='t_cidx_idx' AND indexname='t_cidx_idx_idx_email'")).isEqualTo(1L);
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
}
