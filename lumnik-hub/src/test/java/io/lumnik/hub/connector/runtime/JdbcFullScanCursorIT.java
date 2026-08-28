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
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces the JDBC multi-chunk runaway seen live against a real legacy-ERP source DB:
 * a {@code jdbc-generic} reader in {@code full} mode must advance its PK cursor between
 * chunks. Live, it re-scanned from the start (records_in climbed without bound, never
 * terminating) on a 19k-row table.
 *
 * <p>Deliberately BOUNDED: it drives exactly two {@link ChunkExecutor#executeChunk} calls,
 * feeding chunk 1's returned cursor into chunk 2, and asserts the cursor ADVANCED (chunk 2
 * yields a different next-cursor than chunk 1). This pins the cursor-advance defect without
 * any risk of an unbounded loop / test hang that a full {@code engine.runSync} would suffer.
 *
 * <p>Why existing coverage missed it: hello-world (OFFSET cursor, count=600) is the only
 * multi-chunk IT and advances fine; the JDBC keyset/PK cursor path (JdbcChunkReader full
 * mode + JdbcCursor JSON round-trip) had no multi-chunk coverage.
 *
 * <p>The "source" is the same DevServices Postgres the test runs against, reached via the
 * reader's own JDBC config (URL from the live connection metadata).
 */
@QuarkusTest
class JdbcFullScanCursorIT {

    private static final int CHUNK = 500;
    private static final int ROW_COUNT = 1200;   // > 2 * CHUNK → chunk 2 is non-empty

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

    @BeforeEach
    void seedSourceTable() throws Exception {
        try (Connection c = adminDs.getConnection()) {
            jdbcUrl = c.getMetaData().getURL();
        }
        execSql("DROP TABLE IF EXISTS public.src_demo");
        execSql("CREATE TABLE public.src_demo (src_demo_id bigint primary key, label text)");
        execSql("INSERT INTO public.src_demo (src_demo_id, label) "
              + "SELECT g * 7 + 100, 'row-' || g FROM generate_series(1, " + ROW_COUNT + ") g");
        execSql("GRANT SELECT ON public.src_demo TO " + DB_USER);
    }

    @Test
    void fullScan_secondChunk_advancesCursor() {
        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();
                Connector c = new Connector();
                c.setTenantId(1L);
                c.setName("jdbc-cursor-" + System.nanoTime());
                c.setTypeId("jdbc-generic");
                c.setConfig(Map.of(
                    "url", jdbcUrl,
                    "user", DB_USER,
                    "password", DB_PASSWORD,
                    "dialect", "postgres"));
                connectors.persist(c);
                connectors.flush();

                // One endpoint with unified config keys.
                ConnectorEndpoint endpoint = new ConnectorEndpoint();
                endpoint.setTenantId(1L);
                endpoint.setConnectorId(c.getId());
                endpoint.setName("src_demo");
                endpoint.setMethod("GET");
                endpoint.setPath("/src_demo");
                endpoint.setEnabled(true);
                endpoint.setConfig(Map.of(
                    "schema", "public",
                    "name", "src_demo",
                    "mode", "full",
                    "pk_columns", List.of("src_demo_id")));
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

                // Chunk 1 — from an empty cursor.
                ChunkResult r1 = inTx(() -> exec.executeChunk(reload(run.getId()), type, endpoint, ConnectorCursor.empty()));
                assertThat(r1).isInstanceOf(ChunkResult.Success.class);
                ChunkResult.Success s1 = (ChunkResult.Success) r1;
                assertThat(s1.processed()).as("chunk 1 should read a full page").isEqualTo(CHUNK);

                // Chunk 2 — fed chunk 1's returned cursor (the exact round-trip the engine does).
                ChunkResult r2 = inTx(() -> exec.executeChunk(reload(run.getId()), type, endpoint, s1.nextCursor()));
                assertThat(r2).isInstanceOf(ChunkResult.Success.class);
                ChunkResult.Success s2 = (ChunkResult.Success) r2;
                assertThat(s2.processed()).as("chunk 2 should read rows").isGreaterThan(0);

                // THE ASSERTION: the cursor must move forward between chunks.
                // Bug present → chunk 2 re-reads the same first page → identical next-cursor.
                assertThat(s2.nextCursor().value().toString())
                    .as("chunk 2 cursor must differ from chunk 1 (no re-scan). c1=%s c2=%s",
                        s1.nextCursor().value(), s2.nextCursor().value())
                    .isNotEqualTo(s1.nextCursor().value().toString());

            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    private ConnectorRun reload(UUID runId) {
        return runs.findById(runId);
    }

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
