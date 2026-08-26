package io.lumnik.hub.api.connector;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndpointResourceIT {

    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject UserTransaction utx;

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
        given().header("X-Tenant-Id", "1").header("X-User-Id", "1")
            .when().get("/api/platform/connectors/" + connectorId + "/endpoints")
            .then()
                .statusCode(200)
                .body("$", hasSize(greaterThanOrEqualTo(1)))
                .body("[0].name", equalTo("contacts"));
    }

    @Test
    void get_returns_endpoint_detail() {
        given().header("X-Tenant-Id", "1").header("X-User-Id", "1")
            .when().get("/api/platform/connectors/" + connectorId + "/endpoints/" + endpointId)
            .then()
                .statusCode(200)
                .body("id", equalTo(endpointId))
                .body("path", equalTo("/v1/contacts"))
                .body("method", equalTo("GET"));
    }

    @Test
    void get_returns_404_for_wrong_connector() {
        given().header("X-Tenant-Id", "1").header("X-User-Id", "1")
            .when().get("/api/platform/connectors/00000000-0000-0000-0000-000000000000/endpoints/" + endpointId)
            .then().statusCode(404);
    }

    @Test
    void update_changes_endpoint_fields() {
        given().header("X-Tenant-Id", "1").header("X-User-Id", "1")
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
    void delete_soft_deletes_endpoint() {
        given().header("X-Tenant-Id", "1").header("X-User-Id", "1")
            .when().delete("/api/platform/connectors/" + connectorId + "/endpoints/" + endpointId)
            .then().statusCode(204);

        // After soft-delete, GET should return 404
        given().header("X-Tenant-Id", "1").header("X-User-Id", "1")
            .when().get("/api/platform/connectors/" + connectorId + "/endpoints/" + endpointId)
            .then().statusCode(404);
    }

    @Test
    void reset_cursor_returns_ok_when_no_cursor_exists() {
        given().header("X-Tenant-Id", "1").header("X-User-Id", "1")
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
        given().header("X-Tenant-Id", "1").header("X-User-Id", "1")
            .when().post("/api/platform/connectors/" + connectorId + "/endpoints/" + eid + "/purge")
            .then()
                .statusCode(400)
                .body("error", containsString("out-of-scope"));
    }

    private String createEndpointWithTargetTable(String table) throws Exception {
        String[] holder = new String[1];
        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();
                ConnectorEndpoint ep = new ConnectorEndpoint();
                ep.setConnectorId(java.util.UUID.fromString(connectorId));
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
