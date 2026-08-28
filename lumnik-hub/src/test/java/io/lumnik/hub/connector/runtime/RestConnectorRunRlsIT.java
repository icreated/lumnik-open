package io.lumnik.hub.connector.runtime;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * REST under ENFORCED RLS — parity with CSV's {@link io.lumnik.hub.connector.rls.ConnectorRunRlsIT}.
 *
 * <p>This is where CSV hid prod-only system-mode bugs (finding lm-connector-run-rls-404): the engine
 * reads the connector/endpoint under the run's tenant context, and on an RLS-enforced hub (non-owner
 * {@code lumnik_app} default datasource) a missing GUC means zero rows / a failed run. REST drives the
 * SAME engine + the T1 reader, whose {@code epRepo.findById(endpointId)} lookup MUST resolve under the
 * enforced datasource.
 *
 * <p>Combo: offset pagination + no auth (the simplest from the breadth IT). Seeding mirrors
 * {@code RestEngineBreadthIT} (system-mode native SQL — the sentinel '0' path the engine itself uses);
 * counting + cross-tenant assertions mirror {@code ConnectorRunRlsIT} (explicit GUC transactions on the
 * enforced {@code default} datasource).
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class RestConnectorRunRlsIT {

    @Inject ConnectorEngine engine;
    @Inject EntityManager em;
    @Inject UserTransaction utx;
    @Inject AgroalDataSource appDs;   // default DS = lumnik_app (RLS enforced)

    static WireMockServer wm;

    @BeforeAll
    static void start() {
        wm = new WireMockServer(0);
        wm.start();
        configureFor("localhost", wm.port());
    }

    @AfterAll
    static void stop() {
        if (wm != null) wm.stop();
    }

    @BeforeEach
    void reset() {
        wm.resetAll();
    }

    /** Count rows of {@code sql} inside a tenant GUC transaction on the enforced DS. 0 if the table is absent. */
    private long countAs(long tenant, String sql) {
        try (Connection c = appDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("BEGIN");
            st.execute("SET LOCAL app.current_tenant = '" + tenant + "'");
            try (ResultSet rs = st.executeQuery(sql)) {
                rs.next();
                long n = rs.getLong(1);
                st.execute("COMMIT");
                return n;
            } catch (PSQLException pse) {
                if ("42P01".equals(pse.getSQLState())) { // relation does not exist
                    st.execute("ROLLBACK");
                    return 0L;
                }
                throw new RuntimeException(pse);
            }
        } catch (RuntimeException re) { throw re; }
          catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Seeds a rest-generic connector + endpoint for tenant 1 via the engine's own system-mode path. */
    private UUID seed(String connJson, String path, String epJson) {
        UUID connId = UUID.randomUUID();
        UUID epId = UUID.randomUUID();
        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();
                em.createNativeQuery(
                        "INSERT INTO connector.connector"
                        + "(id, tenant_id, org_id, name, type_id, config)"
                        + " VALUES (:id, 1, 0, :name, 'rest-generic', CAST(:cfg AS jsonb))")
                    .setParameter("id", connId)
                    .setParameter("name", "rest-rls-" + connId)
                    .setParameter("cfg", connJson)
                    .executeUpdate();
                em.createNativeQuery(
                        "INSERT INTO connector.connector_endpoint"
                        + "(id, connector_id, tenant_id, org_id, name, path, config)"
                        + " VALUES (:id, :connId, 1, 0, :name, :path, CAST(:cfg AS jsonb))")
                    .setParameter("id", epId)
                    .setParameter("connId", connId)
                    .setParameter("name", "items-" + epId)
                    .setParameter("path", path)
                    .setParameter("cfg", epJson)
                    .executeUpdate();
                utx.commit();
            } catch (Exception e) {
                try { utx.rollback(); } catch (Exception ignored) { /* rollback best-effort: original exception propagates */ }
                throw new RuntimeException(e);
            }
        });
        return connId;
    }

    @Test
    void rest_run_under_enforced_rls_is_tenant_scoped() {
        String path = "/v1/off";
        String table = "ext.c2_rls_offset_" + Math.abs(System.nanoTime());

        // offset pagination, no auth — two pages of records with ids.
        stubFor(get(urlPathEqualTo(path)).withQueryParam("offset", equalTo("0")).withQueryParam("limit", equalTo("2"))
            .willReturn(okJson("{\"total\":3,\"data\":[{\"id\":\"a\",\"name\":\"X\"},{\"id\":\"b\",\"name\":\"Y\"}]}")));
        stubFor(get(urlPathEqualTo(path)).withQueryParam("offset", equalTo("2")).withQueryParam("limit", equalTo("2"))
            .willReturn(okJson("{\"total\":3,\"data\":[{\"id\":\"c\",\"name\":\"Z\"}]}")));

        UUID connId = seed(
            "{\"kind\":\"rest-generic\",\"base_url\":\"http://localhost:" + wm.port()
                + "\",\"default_rate_limit\":\"100/s\"}",
            path,
            "{\"pagination\":{\"kind\":\"offset\",\"offset_param\":\"offset\",\"limit_param\":\"limit\",\"limit\":2,"
                + "\"total_path\":\"$.total\",\"response_path\":\"$.data\"},"
                + "\"incremental\":{\"mode\":\"full\"},"
                + "\"target\":{\"kind\":\"hybrid\",\"table\":\"" + table + "\",\"unique_key\":\"$.id\","
                + "\"promote\":[{\"name\":\"name\",\"path\":\"$.name\",\"type\":\"text\"}]}}");

        // 1) Run as tenant 1 (system-mode engine path) → Completed.
        RunStatus s = TenantContext.callAsSystem(() -> engine.runSync(connId, new RunTrigger.Manual(1L)));
        assertInstanceOf(RunStatus.Completed.class, s);
        verify(getRequestedFor(urlPathEqualTo(path)).withoutHeader("Authorization"));

        // 2) Rows ARE visible to tenant 1 on the enforced DS.
        assertThat(countAs(1, "SELECT count(*) FROM " + table))
            .as("ingested rows visible to tenant 1").isEqualTo(3L);

        // 3) Rows are INVISIBLE to tenant 2 — RLS scoping on the ext.* hybrid table.
        assertThat(countAs(2, "SELECT count(*) FROM " + table))
            .as("tenant 2 must not read tenant 1's ingested rows").isEqualTo(0L);
    }
}
