package io.lumnik.hub.connector.rest.runtime;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.runtime.ConnectorEngine;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1989: a REST endpoint stored with a target outside ext and connector, or naming one of the hub's own tables, runs
 * to a failure that says so, and the table it named gains nothing. Seeded through SQL, as a connector applied before
 * this release is stored: apply refuses both now.
 */
@QuarkusTest
class HybridTargetRunIT {

    private static final String PATH = "/v1/items";

    @Inject ConnectorEngine engine;
    @Inject EntityManager em;
    @Inject @DataSource("admin") AgroalDataSource admin;

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
    void stub() {
        wm.resetAll();
        stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("[{\"id\":\"a\",\"x\":\"v\"}]")));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "identity.app_user, target.table must be ext.<name> or connector.<name>",
        "connector.connector, connector.connector is not a table this hub's writer made"})
    void a_target_the_writer_may_not_take_fails_the_run_and_leaves_the_table_as_it_was(String table, String says) {
        String[] parts = table.split("\\.");
        List<String> before = columns(parts[0], parts[1]);

        RunStatus s = run(table);

        assertThat(s).isInstanceOf(RunStatus.Failed.class);
        assertThat(((RunStatus.Failed) s).message()).contains(says);
        assertThat(columns(parts[0], parts[1])).as("no column was added to " + table).isEqualTo(before);
    }

    private List<String> columns(String schema, String table) {
        return Jdbc.query(admin, "columns", "SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position",
            ps -> { ps.setString(1, schema); ps.setString(2, table); }, rs -> rs.getString(1));
    }

    /** A connector and one endpoint for tenant 1 whose target is {@code table}, then one run of it. */
    private RunStatus run(String table) {
        UUID connector = UUID.randomUUID();
        UUID endpoint = UUID.randomUUID();
        TenantContext.runAsSystem(() -> QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery(
                    "INSERT INTO connector.connector (id, tenant_id, org_id, name, type_id, config, tags)"
                    + " VALUES (:id, 1, 0, :name, 'rest-generic', CAST(:cfg AS jsonb), '{}')")
                .setParameter("id", connector)
                .setParameter("name", "target-" + connector)
                .setParameter("cfg", "{\"kind\":\"rest-generic\",\"base_url\":\"http://localhost:" + wm.port()
                    + "\",\"default_rate_limit\":\"100/s\"}")
                .executeUpdate();
            em.createNativeQuery(
                    "INSERT INTO connector.connector_endpoint (id, connector_id, tenant_id, org_id, name, path, config)"
                    + " VALUES (:id, :connId, 1, 0, :name, :path, CAST(:cfg AS jsonb))")
                .setParameter("id", endpoint)
                .setParameter("connId", connector)
                .setParameter("name", "items-" + connector)
                .setParameter("path", PATH)
                .setParameter("cfg", "{\"pagination\":{\"kind\":\"none\",\"response_path\":\"$\"},"
                    + "\"incremental\":{\"mode\":\"full\"},"
                    + "\"target\":{\"kind\":\"hybrid\",\"table\":\"" + table + "\",\"unique_key\":\"$.id\","
                    + "\"promote\":[{\"name\":\"grabbed\",\"path\":\"$.x\",\"type\":\"text\"}]}}")
                .executeUpdate();
        }));
        try {
            return TenantContext.callAsSystem(() -> engine.runSync(connector, new RunTrigger.Manual(1L)));
        } finally {
            // Run once and done: no later test that lists the enabled connectors meets it.
            Jdbc.update(admin, "disable", "UPDATE connector.connector SET enabled = false WHERE id = ?",
                ps -> ps.setObject(1, connector));
        }
    }
}
