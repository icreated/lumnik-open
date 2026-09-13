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
 * A {@code jdbc-generic} endpoint with {@code mode: watermark} but NO {@code watermark_column} —
 * the exact shape of the live legacy-ERP connectors that were suspected of causing the WOW Plan 3
 * runaway. It is a misconfiguration {@code JdbcConnectorType.validateConfig} refuses at apply time;
 * this test covers the rows that predate that check, or were written straight to the database.
 *
 * <p>Expected: the reader REFUSES on the first chunk, naming what is missing. The refusal reaches
 * the engine as {@link ChunkResult.Failure} and the run is marked Failed — the misconfiguration is
 * contained, not a runaway, and not a silent full scan either.
 *
 * <p>History: containment used to be an ACCIDENT. Nothing refused anything; the {@code WHERE} branch
 * simply called {@code quoteIdentifier(null)} on chunk 2 and the {@code IllegalArgumentException}
 * ("identifier must not be null or blank") happened to fail the run. Generalising the query builder
 * to a whole-key keyset removed that accident — the query became valid, and watermark mode quietly
 * became a full scan. The refusal is explicit now, so that what contains this misconfiguration is a
 * decision rather than a side effect.
 */
@QuarkusTest
class JdbcWatermarkCursorIT {

    // A handful of rows: the reader refuses before it opens a connection, so the count only has
    // to be non-zero — enough that a regression shows up as Success(processed > 0), not Success(0).
    private static final int ROW_COUNT = 12;

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

                // Chunk 1 — the reader cannot page on a watermark it does not have, and says so
                // instead of reading anything.
                ChunkResult r1 = inTx(() -> exec.executeChunk(reload(run.getId()), type, endpoint, ConnectorCursor.empty(), null));
                assertThat(r1)
                    .as("chunk 1 must refuse (watermark mode, no watermark_column), proving this "
                        + "misconfig is CONTAINED — neither a runaway nor a silent full scan: %s", r1)
                    .isInstanceOf(ChunkResult.Failure.class);
                ChunkResult.Failure f1 = (ChunkResult.Failure) r1;
                assertThat(f1.message())
                    .as("the refusal names the mode and the missing key: %s", f1.message())
                    .contains("watermark").contains("watermark_column");

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
