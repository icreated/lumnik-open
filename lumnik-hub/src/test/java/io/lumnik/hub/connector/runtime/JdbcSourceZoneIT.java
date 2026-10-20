package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1328: a source that writes its times without a zone, in Paris. The connection's {@code zone}
 * reads them there — summer and winter each at their own offset — a date stays its day, and a
 * connection that declares none still reads UTC. Declared on a table already written, it takes
 * effect on the rows a run reads again: they move to the right instant, one row per key.
 */
@QuarkusTest
class JdbcSourceZoneIT {

    private static final String DB_USER = "lumnik_app";
    private static final String DB_PASSWORD = "lumnik_app_dev_password";

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
    private Connector connector;
    private ConnectorEndpoint endpoint;

    @BeforeEach
    void seed() throws Exception {
        try (Connection c = adminDs.getConnection()) { jdbcUrl = c.getMetaData().getURL(); }
        sql("DROP TABLE IF EXISTS public.src_zone");
        sql("DROP TABLE IF EXISTS connector.t_src_zone");
        sql("DELETE FROM connector.dynamic_table WHERE table_name = 't_src_zone'");
        sql("CREATE TABLE public.src_zone (src_zone_id bigint primary key, at timestamp, day date)");
        sql("INSERT INTO public.src_zone VALUES (1, '2026-08-02 23:30', '2026-08-02'), (2, '2026-01-15 23:30', '2026-01-15')");
        sql("GRANT SELECT ON public.src_zone TO " + DB_USER);
        connector = null;
    }

    @Test
    void a_paris_source_lands_each_time_at_its_own_offset() {
        run("Europe/Paris");

        assertThat(landed()).containsExactly(
            "1 2026-08-02 21:30:00 2026-08-02",
            "2 2026-01-15 22:30:00 2026-01-15");
    }

    @Test
    void a_connection_without_a_zone_still_reads_utc() {
        run(null);

        assertThat(landed()).containsExactly(
            "1 2026-08-02 23:30:00 2026-08-02",
            "2 2026-01-15 23:30:00 2026-01-15");
    }

    @Test
    void a_zone_declared_on_a_written_table_moves_the_rows_a_run_reads_again() {
        run(null);

        run("Europe/Paris");

        assertThat(landed()).as("one row per key, at the right instant").containsExactly(
            "1 2026-08-02 21:30:00 2026-08-02",
            "2 2026-01-15 22:30:00 2026-01-15");
    }

    // ── harness ────────────────────────────────────────────────────────────────

    /** One full run of the endpoint, the connection declaring {@code zone} (none when null). */
    private void run(String zone) {
        UUID runId = TenantContext.callAsSystem(() -> inTx(() -> {
            if (connector == null) persistConnector(zone);
            else connectors.findById(connector.getId()).setConfig(connection(zone));
            ConnectorRun run = new ConnectorRun();
            run.setTenantId(1L);
            run.setConnectorId(connector.getId());
            run.setEndpointId(endpoint.getId());
            run.setStatus("Running");
            run.setSyncMode("FullRefresh");
            run.setStartedAt(Instant.now());
            run.setTriggeredBy("MANUAL");
            runs.persist(run);
            runs.flush();
            return run.getId();
        }));
        ConnectorCursor cursor = ConnectorCursor.empty();
        ChunkResult last;
        while (true) {
            ConnectorCursor in = cursor;
            last = TenantContext.callAsSystem(() -> inTx(
                () -> exec.executeChunk(runs.findById(runId), registry.find("jdbc-generic"), endpoint, in, null)));
            if (!(last instanceof ChunkResult.Success s) || s.endOfStream()) break;
            cursor = s.nextCursor();
        }
        assertThat(last).as("%s", last).isInstanceOf(ChunkResult.Success.class);
        TenantContext.callAsSystem(() -> inTx(() -> {
            ConnectorRun ended = runs.findById(runId);
            ended.setStatus("Completed");   // the endpoint takes one run in flight at a time
            ended.setEndedAt(Instant.now());
            return ended;
        }));
    }

    /** Commits what {@code work} did, or rolls it back when it throws — never commits a half-done step. */
    private <T> T inTx(Callable<T> work) throws Exception {
        utx.begin();
        try {
            T result = work.call();
            utx.commit();
            return result;
        } catch (Exception e) {
            utx.rollback();
            throw e;
        }
    }

    /** The connection block, declaring {@code zone} (none when null). */
    private Map<String, Object> connection(String zone) {
        Map<String, Object> config = new HashMap<>(Map.of(
            "url", jdbcUrl, "user", DB_USER, "password", DB_PASSWORD, "dialect", "postgres"));
        if (zone != null) config.put("zone", zone);
        return config;
    }

    private void persistConnector(String zone) {
        connector = new Connector();
        connector.setTenantId(1L);
        connector.setName("jdbc-zone-" + System.nanoTime());
        connector.setTypeId("jdbc-generic");
        connector.setConfig(connection(zone));
        connectors.persist(connector);
        connectors.flush();
        endpoint = new ConnectorEndpoint();
        endpoint.setTenantId(1L);
        endpoint.setConnectorId(connector.getId());
        endpoint.setName("src_zone");
        endpoint.setMethod("GET");
        endpoint.setPath("/src_zone");
        endpoint.setEnabled(true);
        endpoint.setConfig(Map.of("schema", "public", "name", "src_zone", "mode", "full", "pk_columns", List.of("src_zone_id")));
        endpoints.persist(endpoint);
        endpoints.flush();
    }

    /** Every landed row of tenant 1, as {@code key utc-time day}, by key — whatever the session's zone. */
    private List<String> landed() {
        List<String> out = new ArrayList<>();
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT src_zone_id, (at AT TIME ZONE 'UTC')::text, day::text "
                 + "FROM connector.t_src_zone WHERE tenant_id = 1 ORDER BY src_zone_id, id")) {
            while (rs.next()) out.add(rs.getLong(1) + " " + rs.getString(2) + " " + rs.getString(3));
        } catch (Exception e) { throw new RuntimeException(e); }
        return out;
    }

    private void sql(String statement) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(statement);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
