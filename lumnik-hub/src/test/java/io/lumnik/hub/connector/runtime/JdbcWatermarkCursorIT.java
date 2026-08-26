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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterises what ACTUALLY happens when a jdbc-generic connector is created with
 * {@code mode: watermark} but NO {@code watermark-column} — the exact shape of the live
 * legacy-ERP connectors (see /tmp/wc.json). This was investigated as the suspected cause of
 * the WOW Plan 3 runaway ([[project-wow-real-db-run]]); this test DISPROVES that hypothesis.
 *
 * <p>Observed behaviour (proven by this test, stack captured in the run log):
 * <ul>
 *   <li><b>Chunk 1</b> (empty cursor): {@link JdbcQueryBuilder} emits no {@code WHERE}
 *       (hasCursor=false) and skips the watermark in {@code ORDER BY} (watermarkColumn==null),
 *       so it reads a full page and {@link JdbcChunkReader#buildNextCursor} stores a PK-only
 *       cursor (col=""). → {@code Success}.</li>
 *   <li><b>Chunk 2</b> (cursor now has a PK → hasCursor=true): the {@code WHERE} branch calls
 *       {@code dialect.quoteIdentifier(watermarkColumn)} with {@code null} →
 *       {@code IllegalArgumentException: identifier must not be null or blank}
 *       ({@code JdbcQueryBuilder:29} → {@code PostgresDialect.quoteIdentifier:28}). The
 *       {@code ChunkExecutor} catch turns it into {@code ChunkResult.Failure}.</li>
 * </ul>
 *
 * <p>So the engine ingests ONE chunk then marks the run <b>Failed</b> — it does NOT loop
 * forever. The misconfiguration is contained (not a runaway), though the failure is cryptic
 * and late (chunk 2, not config time). The real runaway cause is therefore still elsewhere;
 * the proven-good path is {@link JdbcFullScanCursorIT} (full mode + valid PK, GREEN).
 *
 * <p>This test is GREEN: it asserts chunk 1 succeeds and chunk 2 fails fast with the
 * identifier error — a regression guard pinning the current (sub-optimal) behaviour.
 */
@QuarkusTest
class JdbcWatermarkCursorIT {

    private static final int CHUNK = 500;
    private static final int ROW_COUNT = 1200;   // > 2 * CHUNK → chunk 2 is a full page too

    @Inject ChunkExecutor exec;
    @Inject ConnectorTypeRegistry registry;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject ConnectorRunRepository runs;
    @Inject UserTransaction utx;

    @Inject
    @io.quarkus.agroal.DataSource("admin")
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
        execSql("DROP TABLE IF EXISTS public.src_wm");
        execSql("CREATE TABLE public.src_wm (src_wm_id bigint primary key, updated timestamptz, label text)");
        // Mirror a bulk-imported legacy table: many rows, a real PK, an 'updated' column we DON'T configure.
        execSql("INSERT INTO public.src_wm (src_wm_id, updated, label) "
              + "SELECT g, now(), 'row-' || g FROM generate_series(1, " + ROW_COUNT + ") g");
        execSql("GRANT SELECT ON public.src_wm TO " + DB_USER);
    }

    @Test
    void watermarkMode_withoutWatermarkColumn_failsFast_notRunaway() {
        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();
                Connector c = new Connector();
                c.setTenantId(1L);
                c.setName("jdbc-wm-" + System.nanoTime());
                c.setTypeId("jdbc-generic");
                c.setConfig(Map.of(
                    "url", jdbcUrl,
                    "user", DB_USER,
                    "password", DB_PASSWORD,
                    "dialect", "postgres"));
                connectors.persist(c);
                connectors.flush();

                // Endpoint mirrors the live misconfig: mode=watermark, NO watermark_column.
                ConnectorEndpoint endpoint = new ConnectorEndpoint();
                endpoint.setTenantId(1L);
                endpoint.setConnectorId(c.getId());
                endpoint.setName("src_wm");
                endpoint.setMethod("GET");
                endpoint.setPath("/src_wm");
                endpoint.setEnabled(true);
                endpoint.setConfig(Map.of(
                    "schema", "public",
                    "name", "src_wm",
                    "mode", "watermark",                 // <-- the live config
                    "pk_columns", List.of("src_wm_id")   // present but UNUSED in watermark mode
                    // NO "watermark_column" key  <-- exactly how the live connectors were made
                    ));
                endpoints.persist(endpoint);
                endpoints.flush();

                ConnectorRun run = new ConnectorRun();
                run.setTenantId(1L);
                run.setConnectorId(c.getId());
                run.setEndpointId(endpoint.getId());
                run.setStatus("Running");
                run.setSyncMode("Incremental");
                run.setStartedAt(Instant.now());
                run.setTriggeredBy("MANUAL");
                runs.persist(run);
                runs.flush();
                utx.commit();

                ConnectorType type = registry.find("jdbc-generic");

                // Chunk 1 — empty cursor: reads a full page, builds a PK-only next cursor.
                ChunkResult r1 = inTx(() -> exec.executeChunk(reload(run.getId()), type, endpoint, ConnectorCursor.empty()));
                assertThat(r1)
                    .as("chunk 1 should succeed (no WHERE yet): %s", r1)
                    .isInstanceOf(ChunkResult.Success.class);
                ChunkResult.Success s1 = (ChunkResult.Success) r1;
                assertThat(s1.processed()).as("chunk 1 reads a full page").isEqualTo(CHUNK);

                // Chunk 2 — fed chunk 1's PK cursor: the WHERE branch now needs the watermark
                // identifier, which is null → fail fast (NOT an infinite re-read).
                ChunkResult r2 = inTx(() -> exec.executeChunk(reload(run.getId()), type, endpoint, s1.nextCursor()));
                assertThat(r2)
                    .as("chunk 2 must fail fast (watermark mode + null watermark-column), "
                        + "proving this misconfig is CONTAINED, not a runaway: %s", r2)
                    .isInstanceOf(ChunkResult.Failure.class);
                ChunkResult.Failure f2 = (ChunkResult.Failure) r2;
                assertThat(f2.message())
                    .as("the failure is the null watermark identifier")
                    .contains("null or blank");

            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    private ConnectorRun reload(UUID runId) {
        return runs.findById(runId);
    }

    private ChunkResult inTx(java.util.concurrent.Callable<ChunkResult> work) throws Exception {
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
