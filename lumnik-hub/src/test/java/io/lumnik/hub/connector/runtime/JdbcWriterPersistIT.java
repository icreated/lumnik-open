package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.ConnectorType;
import io.lumnik.hub.connector.api.types.ChunkResult;
import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.connector.registry.ConnectorTypeRegistry;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the jdbc-generic WRITER persists read rows into the hub table {@code connector.t_<name>}
 * (auto-schema 1:1 dump via MappingEngine → DynamicTableWriter). Before this, a bare
 * jdbc-generic connector only counted reads and created no table.
 */
@QuarkusTest
class JdbcWriterPersistIT {

    private static final int ROWS = 30;

    @Inject ChunkExecutor exec;
    @Inject ConnectorTypeRegistry registry;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject ConnectorRunRepository runs;
    @Inject UserTransaction utx;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    private String jdbcUrl;
    private static final String DB_USER = "lumnik_app";
    private static final String DB_PASSWORD = "lumnik_app_dev_password";

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

    @BeforeEach
    void seed() throws Exception {
        try (Connection c = adminDs.getConnection()) { jdbcUrl = c.getMetaData().getURL(); }
        execSql("DROP TABLE IF EXISTS public.src_wp");
        execSql("DROP TABLE IF EXISTS connector.t_src_wp");
        execSql("CREATE TABLE public.src_wp (src_wp_id bigint primary key, label text, amount numeric)");
        execSql("INSERT INTO public.src_wp (src_wp_id, label, amount) "
              + "SELECT g, 'row-' || g, g * 1.5 FROM generate_series(1, " + ROWS + ") g");
        execSql("GRANT SELECT ON public.src_wp TO " + DB_USER);
    }

    @Test
    void readRows_arePersisted_toHubTable() {
        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();
                Connector c = new Connector();
                c.setTenantId(1L);
                c.setName("jdbc-writer-" + System.nanoTime());
                c.setTypeId("jdbc-generic");
                c.setConfig(Map.of("url", jdbcUrl, "user", DB_USER, "password", DB_PASSWORD, "dialect", "postgres"));
                connectors.persist(c);
                connectors.flush();

                ConnectorEndpoint endpoint = new ConnectorEndpoint();
                endpoint.setTenantId(1L);
                endpoint.setConnectorId(c.getId());
                endpoint.setName("src_wp");
                endpoint.setMethod("GET");
                endpoint.setPath("/src_wp");
                endpoint.setEnabled(true);
                endpoint.setConfig(Map.of(
                    "schema", "public", "name", "src_wp", "mode", "full",
                    "pk_columns", List.of("src_wp_id")));
                endpoints.persist(endpoint);
                endpoints.flush();

                ConnectorRun run = new ConnectorRun();
                run.setTenantId(1L);
                run.setConnectorId(c.getId());
                run.setEndpointId(endpoint.getId());
                run.setStatus("Running");
                run.setSyncMode("FullRefresh");
                run.setStartedAt(Instant.now());
                run.setTriggeredBy("MANUAL");
                runs.persist(run);
                runs.flush();
                utx.commit();

                ConnectorType type = registry.find("jdbc-generic");

                // One chunk reads all 30 rows (EndOfStream) and the writer persists them.
                ChunkResult r = inTx(() -> exec.executeChunk(reload(run.getId()), type, endpoint, ConnectorCursor.empty()));
                assertThat(r).isInstanceOf(ChunkResult.Success.class);
                assertThat(((ChunkResult.Success) r).processed()).isEqualTo(ROWS);

            } catch (Exception e) { throw new RuntimeException(e); }
        });

        // The hub table was created and populated 1:1.
        assertThat(scalar("SELECT count(*) FROM connector.t_src_wp"))
            .as("read rows must be persisted to connector.t_src_wp")
            .isEqualTo(ROWS);
        // Source columns were inferred (auto-schema), not just lineage columns.
        assertThat(scalar("SELECT count(*) FROM information_schema.columns "
              + "WHERE table_schema='connector' AND table_name='t_src_wp' AND column_name IN ('src_wp_id','label','amount')"))
            .as("source columns inferred onto the hub table")
            .isEqualTo(3);
    }

    private ConnectorRun reload(UUID runId) { return runs.findById(runId); }

    private ChunkResult inTx(Callable<ChunkResult> work) throws Exception {
        utx.begin();
        try {
            ChunkResult r = work.call();
            utx.commit();
            return r;
        } catch (Exception e) {
            try { utx.rollback(); } catch (Exception ignored) {}
            throw e;
        }
    }
}
