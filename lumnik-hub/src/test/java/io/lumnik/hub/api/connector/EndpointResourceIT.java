package io.lumnik.hub.api.connector;

import io.agroal.api.AgroalDataSource;
import io.lumnik.testkit.auth.TestIdentity;
import io.lumnik.common.tenant.TenantContext;
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
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndpointResourceIT {

    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject UserTransaction utx;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    String connectorId;
    String endpointId;

    @BeforeEach
    void setup() throws Exception {
        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();

                Connector c = new Connector();
                c.setTenantId(1L);
                c.setName("ep-test-connector-" + System.nanoTime());
                c.setTypeId("rest-generic");
                c.setConfig(Map.of("kind", "rest-generic", "base_url", "https://api.example.com"));
                c.setEnabled(true);
                connectors.persist(c);

                ConnectorEndpoint ep = new ConnectorEndpoint();
                ep.setConnectorId(c.getId());
                ep.setTenantId(1L);
                ep.setName("contacts");
                ep.setMethod("GET");
                ep.setPath("/v1/contacts");
                ep.setConfig(Map.of("pagination", Map.of("kind", "cursor")));
                ep.setEnabled(true);
                endpoints.persist(ep);

                utx.commit();

                connectorId = c.getId().toString();
                endpointId  = ep.getId().toString();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void list_returns_endpoints_for_connector() {
        given().headers(TestIdentity.tenantAndUser(1))
            .when().get("/api/platform/connectors/" + connectorId + "/endpoints")
            .then()
                .statusCode(200)
                .body("$", hasSize(greaterThanOrEqualTo(1)))
                .body("[0].name", equalTo("contacts"));
    }

    @Test
    void get_returns_endpoint_detail() {
        given().headers(TestIdentity.tenantAndUser(1))
            .when().get("/api/platform/connectors/" + connectorId + "/endpoints/" + endpointId)
            .then()
                .statusCode(200)
                .body("id", equalTo(endpointId))
                .body("path", equalTo("/v1/contacts"))
                .body("method", equalTo("GET"));
    }

    @Test
    void get_returns_404_for_wrong_connector() {
        given().headers(TestIdentity.tenantAndUser(1))
            .when().get("/api/platform/connectors/00000000-0000-0000-0000-000000000000/endpoints/" + endpointId)
            .then().statusCode(404);
    }

    /**
     * The pre-flight route had no test at all — which is how `lm source rest test` shipped sending
     * an endpoint id of "*" that this UUID path param could never bind. These pin the guard around
     * it; the composition itself is unit-tested in {@code RestPreflightTest}.
     */
    @Test
    void test_returns_404_for_wrong_connector() {
        given().headers(TestIdentity.tenantAndUser(1))
            .when().post("/api/platform/connectors/00000000-0000-0000-0000-000000000000/endpoints/"
                         + endpointId + "/test")
            .then().statusCode(404);
    }

    @Test
    void test_returns_404_for_an_unknown_endpoint() {
        given().headers(TestIdentity.tenantAndUser(1))
            .when().post("/api/platform/connectors/" + connectorId
                         + "/endpoints/00000000-0000-0000-0000-000000000000/test")
            .then().statusCode(404);
    }

    @Test
    void update_changes_endpoint_fields() {
        given().headers(TestIdentity.tenantAndUser(1))
            .contentType("application/json")
            .body("""
                {
                  "method": "GET",
                  "path": "/v2/contacts",
                  "enabled": true,
                  "config": {"pagination": {"kind": "page"}}
                }
                """)
            .when().put("/api/platform/connectors/" + connectorId + "/endpoints/" + endpointId)
            .then()
                .statusCode(200)
                .body("path", equalTo("/v2/contacts"));
    }

    @Test
    void update_refuses_rename_into_a_system_column() {
        // The endpoint config door is the sibling of ConnectorAdminResource: it persisted config
        // with no validation. For jdbc the per-table `rename` lives here (top-level), and a rename
        // INTO a hub system column kills the run via JdbcConnectorType.validateRename — refuse it
        // here at write time, the same MapColumnsRules the connector door and the run backstop share.
        given().headers(TestIdentity.tenantAndUser(1))
            .contentType("application/json")
            .body("""
                {
                  "method": "GET",
                  "path": "/v1/contacts",
                  "enabled": true,
                  "config": {"rename": {"CUST_NM": "tenant_id"}}
                }
                """)
            .when().put("/api/platform/connectors/" + connectorId + "/endpoints/" + endpointId)
            .then()
                .statusCode(400)
                .body("error", containsString("tenant_id"));
    }

    /** #1475: a mapping tier written as a map was stored, and refused only by the run (#1464). */
    @Test
    void update_refuses_a_mapping_tier_written_as_a_map() {
        given().headers(TestIdentity.tenantAndUser(1))
            .contentType("application/json")
            .body("""
                {
                  "method": "GET",
                  "path": "/v1/contacts",
                  "enabled": true,
                  "config": {"mapping": {"cell": {"upper": {"columns": ["name"]}}}}
                }
                """)
            .when().put("/api/platform/connectors/" + connectorId + "/endpoints/" + endpointId)
            .then()
                .statusCode(400)
                .body("error", equalTo("config 'mapping.cell' cell must be a list of hooks, "
                    + "e.g. - upper: { columns: [name] } (got object)"));
    }

    @Test
    void delete_soft_deletes_endpoint() {
        given().headers(TestIdentity.tenantAndUser(1))
            .when().delete("/api/platform/connectors/" + connectorId + "/endpoints/" + endpointId)
            .then().statusCode(204);

        // After soft-delete, GET should return 404
        given().headers(TestIdentity.tenantAndUser(1))
            .when().get("/api/platform/connectors/" + connectorId + "/endpoints/" + endpointId)
            .then().statusCode(404);
    }

    @Test
    void reset_cursor_returns_ok_when_no_cursor_exists() {
        given().headers(TestIdentity.tenantAndUser(1))
            .when().post("/api/platform/connectors/" + connectorId + "/endpoints/" + endpointId + "/reset-cursor")
            .then()
                .statusCode(200)
                .body("deleted", notNullValue());
    }

    @Test
    void purge_rejects_out_of_scope_table() throws Exception {
        // An endpoint whose target.table points outside the purgeable data schemas must be
        // refused (400) before any DELETE — bounding the blast radius away from identity/core/rag.
        String eid = createEndpointWithTargetTable("identity.app_user");
        given().headers(TestIdentity.tenantAndUser(1))
            .when().post("/api/platform/connectors/" + connectorId + "/endpoints/" + eid + "/purge")
            .then()
                .statusCode(400)
                .body("error", containsString("out-of-scope"));
    }

    /**
     * The one refusal both non-hybrid cases give, in the integrator's terms (#1181). Copied, not
     * read from {@code EndpointResource}: the test pins the wire text, so a rewording must be seen here.
     */
    private static final String NOT_PURGEABLE_ON_ITS_OWN =
        "This endpoint writes to a connector table whose rows are not tracked per endpoint, so it "
        + "cannot be purged on its own. Deleting the connector removes its data, along with its run history.";

    @Test
    void purge_refuses_an_endpoint_without_a_hybrid_target_in_the_integrators_terms() {
        // The usual JDBC/CSV endpoint: its rows land in a connector.t_* table the config never
        // names. The refusal says what the integrator can do, never mentions target.table (#1181).
        given().headers(TestIdentity.tenantAndUser(1))
            .when().post("/api/platform/connectors/" + connectorId + "/endpoints/" + endpointId + "/purge")
            .then()
                .statusCode(400)
                .body("error", equalTo(NOT_PURGEABLE_ON_ITS_OWN));
    }

    @Test
    void purge_rejects_an_in_scope_table_without_an_endpoint_id_column() throws Exception {
        // A connector.t_* table (DynamicTableWriter) is in a purgeable schema but has no endpoint
        // column — purge-by-endpoint is undefined there. The guard is per-table, not just per-schema:
        // a clean 400, not the raw 500 the DELETE would raise hitting a missing column, in the same
        // words as the no-target case — never the internal column's name (#1181).
        String table = "t_noepid_" + System.nanoTime();
        exec("CREATE TABLE connector.\"" + table + "\" (id bigint)");
        try {
            String eid = createEndpointWithTargetTable("connector." + table);
            given().headers(TestIdentity.tenantAndUser(1))
                .when().post("/api/platform/connectors/" + connectorId + "/endpoints/" + eid + "/purge")
                .then()
                    .statusCode(400)
                    .body("error", equalTo(NOT_PURGEABLE_ON_ITS_OWN));
        } finally {
            exec("DROP TABLE IF EXISTS connector.\"" + table + "\"");
        }
    }

    @Test
    void purge_of_a_hybrid_target_not_created_yet_deletes_nothing() throws Exception {
        // A hybrid endpoint that has never run names a table that does not exist yet: there is
        // nothing to purge, and "delete the connector" would be the wrong advice (#1181).
        String eid = createEndpointWithTargetTable("connector.never_written_" + System.nanoTime());
        given().headers(TestIdentity.tenantAndUser(1))
            .when().post("/api/platform/connectors/" + connectorId + "/endpoints/" + eid + "/purge")
            .then()
                .statusCode(200)
                .body("deleted", equalTo(0));
    }

    @Test
    void purge_dry_run_refuses_exactly_like_the_purge() {
        // The CLI asks the dry run BEFORE its confirmation prompt, so the refusal must arrive there.
        given().headers(TestIdentity.tenantAndUser(1))
            .when().post("/api/platform/connectors/" + connectorId + "/endpoints/" + endpointId + "/purge?dryRun=true")
            .then()
                .statusCode(400)
                .body("error", equalTo(NOT_PURGEABLE_ON_ITS_OWN));
    }

    @Test
    void purge_dry_run_counts_the_endpoints_rows_and_deletes_nothing() throws Exception {
        String table = "hybrid_dryrun_" + System.nanoTime();
        exec("CREATE TABLE connector.\"" + table + "\" (id bigint, endpoint_id uuid)");
        try {
            String eid = createEndpointWithTargetTable("connector." + table);
            exec("INSERT INTO connector.\"" + table + "\" VALUES (1, '" + eid + "'), (2, '" + eid + "'), "
                + "(3, '" + UUID.randomUUID() + "')");
            String purge = "/api/platform/connectors/" + connectorId + "/endpoints/" + eid + "/purge";

            given().headers(TestIdentity.tenantAndUser(1))
                .when().post(purge + "?dryRun=true")
                .then()
                    .statusCode(200)
                    .body("deleted", equalTo(2))
                    .body("table", equalTo("connector." + table))
                    .body("dryRun", equalTo(true));
            assertThat(count("connector.\"" + table + "\"")).as("a dry run deletes nothing").isEqualTo(3);

            given().headers(TestIdentity.tenantAndUser(1))
                .when().post(purge)
                .then()
                    .statusCode(200)
                    .body("deleted", equalTo(2))
                    .body("dryRun", equalTo(false));
            assertThat(count("connector.\"" + table + "\"")).as("the other endpoint's row stays").isEqualTo(1);
        } finally {
            exec("DROP TABLE IF EXISTS connector.\"" + table + "\"");
        }
    }

    private long count(String fqn) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM " + fqn)) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void exec(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String createEndpointWithTargetTable(String table) throws Exception {
        String[] holder = new String[1];
        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();
                ConnectorEndpoint ep = new ConnectorEndpoint();
                ep.setConnectorId(UUID.fromString(connectorId));
                ep.setTenantId(1L);
                ep.setName("purge-target-" + System.nanoTime());
                ep.setMethod("GET");
                ep.setPath("/v1/x");
                ep.setConfig(Map.of("target", Map.of("table", table)));
                ep.setEnabled(true);
                endpoints.persist(ep);
                utx.commit();
                holder[0] = ep.getId().toString();
            } catch (Exception e) {
                try { utx.rollback(); } catch (Exception rb) { e.addSuppressed(rb); }
                throw new RuntimeException(e);
            }
        });
        return holder[0];
    }
}
