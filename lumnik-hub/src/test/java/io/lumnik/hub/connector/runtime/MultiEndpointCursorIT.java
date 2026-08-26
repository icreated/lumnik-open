package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.ConnectorType;
import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorCursorEntity;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.connector.registry.ConnectorTypeRegistry;
import io.lumnik.hub.connector.repository.ConnectorCursorEntityRepository;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.lumnik.hub.testsupport.EventItSupport;
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
 * Regression: a connector with MULTIPLE endpoints must get one cursor row PER endpoint.
 * The cursor PK was connector_id (V18), so a second endpoint of the same connector collided
 * ("duplicate key ... connector_cursor_pkey"). V19 re-keys the cursor to endpoint_id.
 */
@QuarkusTest
class MultiEndpointCursorIT {

    @Inject ChunkExecutor exec;
    @Inject ConnectorTypeRegistry registry;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject ConnectorRunRepository runs;
    @Inject ConnectorCursorEntityRepository cursors;
    @Inject UserTransaction utx;

    @Inject @io.quarkus.agroal.DataSource("admin") AgroalDataSource adminDs;

    private String jdbcUrl;
    private static final String U = "lumnik_app", P = "lumnik_app_dev_password";

    private void execSql(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) { st.execute(sql); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    @BeforeEach
    void seed() throws Exception {
        try (Connection c = adminDs.getConnection()) { jdbcUrl = c.getMetaData().getURL(); }
        for (String t : List.of("src_a", "src_b")) {
            execSql("DROP TABLE IF EXISTS public." + t);
            execSql("DROP TABLE IF EXISTS connector.t_" + t);
            execSql("CREATE TABLE public." + t + " (" + t + "_id bigint primary key, label text)");
            // > default chunk size (500) so the first chunk is HasMore → persistCursor runs
            execSql("INSERT INTO public." + t + " (" + t + "_id, label) SELECT g, 'r'||g FROM generate_series(1,600) g");
            execSql("GRANT SELECT ON public." + t + " TO " + U);
        }
    }

    @Test
    void two_endpoints_one_connector_get_separate_cursor_rows() {
        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();
                Connector c = new Connector();
                c.setTenantId(1L);
                c.setName("multi-" + System.nanoTime());
                c.setTypeId("jdbc-generic");
                c.setConfig(Map.of("url", jdbcUrl, "user", U, "password", P, "dialect", "postgres"));
                connectors.persist(c); connectors.flush();

                UUID epA = makeEndpoint(c.getId(), "src_a");
                UUID epB = makeEndpoint(c.getId(), "src_b");
                ConnectorEndpoint endpointA = endpoints.findById(epA);
                ConnectorEndpoint endpointB = endpoints.findById(epB);
                UUID runA = makeRun(c.getId(), epA);
                UUID runB = makeRun(c.getId(), epB);
                utx.commit();

                ConnectorType type = registry.find("jdbc-generic");

                // Both endpoints of the SAME connector persist their cursor — must not collide.
                EventItSupport.runInTxAsSystem(utx, () -> { exec.executeChunk(runs.findById(runA), type, endpointA, ConnectorCursor.empty()); return null; });
                EventItSupport.runInTxAsSystem(utx, () -> { exec.executeChunk(runs.findById(runB), type, endpointB, ConnectorCursor.empty()); return null; });

                ConnectorCursorEntity ca = EventItSupport.runInTxAsSystem(utx, () -> cursors.findByEndpoint(epA));
                ConnectorCursorEntity cb = EventItSupport.runInTxAsSystem(utx, () -> cursors.findByEndpoint(epB));
                assertThat(ca).as("endpoint A has its own cursor row").isNotNull();
                assertThat(cb).as("endpoint B has its own cursor row").isNotNull();
                assertThat(ca.getEndpointId()).isEqualTo(epA);
                assertThat(cb.getEndpointId()).isEqualTo(epB);
                assertThat(ca.getConnectorId()).as("both back-reference the same connector")
                    .isEqualTo(cb.getConnectorId());
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    private UUID makeEndpoint(UUID connectorId, String table) {
        ConnectorEndpoint ep = new ConnectorEndpoint();
        ep.setTenantId(1L); ep.setConnectorId(connectorId); ep.setName(table);
        ep.setMethod("GET"); ep.setPath("/" + table); ep.setEnabled(true);
        ep.setConfig(Map.of("schema", "public", "name", table, "mode", "full",
            "pk_columns", List.of(table + "_id")));
        endpoints.persist(ep); endpoints.flush();
        return ep.getId();
    }

    private UUID makeRun(UUID connectorId, UUID endpointId) {
        ConnectorRun r = new ConnectorRun();
        r.setTenantId(1L); r.setConnectorId(connectorId); r.setEndpointId(endpointId);
        r.setStatus("Running"); r.setSyncMode("FullRefresh"); r.setStartedAt(Instant.now()); r.setTriggeredBy("MANUAL");
        runs.persist(r); runs.flush();
        return r.getId();
    }
}
