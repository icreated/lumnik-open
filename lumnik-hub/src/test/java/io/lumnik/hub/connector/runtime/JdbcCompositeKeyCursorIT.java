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
import io.lumnik.hub.testsupport.EventItSupport;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A composite-key cursor has to survive the storage it is kept in. {@code ConnectorEngine} does
 * NOT hand chunk N's cursor to chunk N+1 in memory: it RE-READS it from {@code connector_cursor}
 * on every iteration ({@code ConnectorEngine.currentCursor}, L1 cache cleared first). That column
 * is {@code jsonb}, and <b>jsonb does not preserve the key order of an object</b> — it stores keys
 * shortest-first. A cursor written as {@code {order_id, line_no}} comes back as
 * {@code {line_no, order_id}}.
 *
 * <p>Found live, not here: a reader that compared the cursor's key ORDER against its declared
 * {@code pk_columns} declared its OWN cursor foreign on every chunk and restarted the table each
 * time — 2.3 million rows read in one minute on a 700-row table before the run was cancelled.
 * Every in-memory IT stayed green: a JsonNode built in the JVM keeps its insertion order, so the
 * seam that breaks is the one no test crossed.
 *
 * <p>So this IT drives two chunks the way the engine does — reloading the cursor from the database
 * between them, with the persistence context cleared, exactly as {@code currentCursor} does.
 */
@QuarkusTest
class JdbcCompositeKeyCursorIT {

    private static final int CHUNK = 500;
    private static final int ORDERS = 200;
    private static final int LINES_PER_ORDER = 7;   // 1400 rows → chunk 2 is a full page too

    @Inject ChunkExecutor exec;
    @Inject ConnectorTypeRegistry registry;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject ConnectorRunRepository runs;
    @Inject ConnectorEngine engine;
    @Inject UserTransaction utx;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    private String jdbcUrl;
    private static final String DB_USER = "lumnik_app";
    private static final String DB_PASSWORD = "lumnik_app_dev_password";

    @BeforeEach
    void seedSourceTable() throws Exception {
        try (Connection c = adminDs.getConnection()) {
            jdbcUrl = c.getMetaData().getURL();
        }
        EventItSupport.execSql(adminDs, "DROP TABLE IF EXISTS public.src_lines");
        EventItSupport.execSql(adminDs, "CREATE TABLE public.src_lines (order_id bigint, line_no int, label text,"
              + " PRIMARY KEY (order_id, line_no))");
        EventItSupport.execSql(adminDs, "INSERT INTO public.src_lines (order_id, line_no, label) "
              + "SELECT o, l, 'line-' || o || '-' || l"
              + " FROM generate_series(1, " + ORDERS + ") o, generate_series(1, " + LINES_PER_ORDER + ") l");
        EventItSupport.execSql(adminDs, "GRANT SELECT ON public.src_lines TO " + DB_USER);
    }

    @Test
    void the_reader_accepts_its_own_cursor_after_a_jsonb_round_trip() {
        TenantContext.runAsSystem(() -> {
            try {
                ConnectorEndpoint endpoint = createConnectorAndEndpoint();
                ConnectorRun run = createRun(endpoint);
                ConnectorType type = registry.find("jdbc-generic");

                ChunkResult r1 = EventItSupport.runInTxAsSystem(utx, () ->
                    exec.executeChunk(reload(run.getId()), type, endpoint, ConnectorCursor.empty(), null));
                assertThat(r1).isInstanceOf(ChunkResult.Success.class);
                assertThat(((ChunkResult.Success) r1).processed())
                    .as("chunk 1 reads a full page").isEqualTo(CHUNK);

                // What the DATABASE gives back — not what the JVM built.
                ConnectorCursor stored = engine.currentCursor(endpoint.getId());
                assertThat(keyNames(stored))
                    .as("the stored cursor carries every key column: %s", stored.value())
                    .containsExactlyInAnyOrder("order_id", "line_no");

                ChunkResult r2 = EventItSupport.runInTxAsSystem(utx, () ->
                    exec.executeChunk(reload(run.getId()), type, endpoint, stored, null));
                assertThat(r2).isInstanceOf(ChunkResult.Success.class);
                assertThat(((ChunkResult.Success) r2).processed())
                    .as("chunk 2 reads rows").isGreaterThan(0);

                // THE ASSERTION: chunk 2 must move past chunk 1. A reader that rejects its own
                // reloaded cursor restarts the table and lands on the very same last row.
                // Both positions read from the DATABASE. The in-memory cursor cannot be used here:
                // it is built with putPOJO, so `path("pk").path("order_id")` finds nothing on it
                // and every comparison against it would silently pass.
                long orderAfter1 = pkValue(stored, "order_id");
                long orderAfter2 = pkValue(engine.currentCursor(endpoint.getId()), "order_id");
                assertThat(orderAfter1)
                    .as("guard: chunk 1's stored position must be a real row, not a missing node")
                    .isGreaterThan(0);
                assertThat(orderAfter2)
                    .as("the cursor advanced across the reload (no restart): chunk1=%d chunk2=%d",
                        orderAfter1, orderAfter2)
                    .isGreaterThan(orderAfter1);

            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    private static List<String> keyNames(ConnectorCursor c) {
        List<String> names = new ArrayList<>();
        c.value().path("pk").fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static long pkValue(ConnectorCursor c, String column) {
        return c.value().path("pk").path(column).asLong();
    }

    private ConnectorEndpoint createConnectorAndEndpoint() throws Exception {
        utx.begin();
        Connector c = new Connector();
        c.setTenantId(1L);
        c.setName("jdbc-composite-" + System.nanoTime());
        c.setTypeId("jdbc-generic");
        c.setConfig(Map.of("url", jdbcUrl, "user", DB_USER,
            "password", DB_PASSWORD, "dialect", "postgres"));
        connectors.persist(c);
        connectors.flush();

        ConnectorEndpoint endpoint = new ConnectorEndpoint();
        endpoint.setTenantId(1L);
        endpoint.setConnectorId(c.getId());
        endpoint.setName("src_lines");
        endpoint.setMethod("GET");
        endpoint.setPath("/src_lines");
        endpoint.setEnabled(true);
        endpoint.setConfig(Map.of(
            "schema", "public",
            "name", "src_lines",
            "mode", "full",
            "pk_columns", List.of("order_id", "line_no")));
        endpoints.persist(endpoint);
        endpoints.flush();
        utx.commit();
        return endpoint;
    }

    private ConnectorRun createRun(ConnectorEndpoint endpoint) throws Exception {
        utx.begin();
        ConnectorRun run = new ConnectorRun();
        run.setTenantId(1L);
        run.setConnectorId(endpoint.getConnectorId());
        run.setEndpointId(endpoint.getId());
        run.setStatus("Running");
        run.setSyncMode("FullRefresh");
        run.setStartedAt(Instant.now());
        run.setTriggeredBy("MANUAL");
        runs.persist(run);
        runs.flush();
        utx.commit();
        return run;
    }

    private ConnectorRun reload(UUID runId) {
        return runs.findById(runId);
    }

}
