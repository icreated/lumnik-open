package io.lumnik.hub.api.connector;

import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class ConnectorApplyResourceIT {

    private static final RestAssuredConfig YAML_CFG = RestAssuredConfig.config()
        .encoderConfig(EncoderConfig.encoderConfig()
            .encodeContentTypeAs("application/yaml", ContentType.TEXT));

    private static final String YAML_BODY = """
        apiVersion: connectors.lumnik.io/v1
        kind: Connector
        metadata:
          name: test-apply-%d
          connector_type: rest-generic
          tenant: 1
          scopes: [crm, test]
        spec:
          base_url: https://api.example.com
          auth:
            kind: bearer
            token_env: EXAMPLE_API_KEY
          endpoints:
            - id: contacts
              method: GET
              path: /v1/contacts
              pagination:
                kind: cursor
                cursor_param: after
                cursor_path: "$.data[-1:].id"
                has_more_path: "$.has_more"
                response_path: "$.data"
              target:
                kind: hybrid
                table: ext.contacts
                unique_key: "$.id"
                promote:
                  - { name: name, path: "$.name", type: text }
        """.formatted(System.nanoTime());

    @Test
    void apply_creates_connector_and_endpoints() {
        given()
            .config(YAML_CFG)
            .headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml")
            .body(YAML_BODY)
        .when().post("/api/platform/connectors/apply")
        .then()
            .statusCode(200)
            .body("name", startsWith("test-apply-"))
            .body("endpointsApplied", equalTo(1))
            .body("connectorId", notNullValue());
    }

    @Test
    void apply_is_idempotent() {
        String body = """
            apiVersion: connectors.lumnik.io/v1
            kind: Connector
            metadata:
              name: idempotent-apply-%d
              connector_type: rest-generic
              tenant: 1
            spec:
              base_url: https://api.example.com
              auth:
                kind: bearer
                token_env: EXAMPLE_API_KEY
              endpoints:
                - id: items
                  method: GET
                  path: /items
                  pagination:
                    kind: cursor
                    cursor_param: after
                    cursor_path: "$.data[-1:].id"
                    has_more_path: "$.has_more"
                    response_path: "$.data"
                  target:
                    kind: hybrid
                    table: ext.items
                    unique_key: "$.id"
                    promote:
                      - { name: name, path: "$.name", type: text }
            """.formatted(System.nanoTime());

        // First apply
        String connectorId = given()
            .config(YAML_CFG)
            .headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml")
            .body(body)
        .when().post("/api/platform/connectors/apply")
        .then()
            .statusCode(200)
            .body("endpointsApplied", equalTo(1))
            .extract().path("connectorId");

        // Second apply — same YAML, same connector should be returned
        given()
            .config(YAML_CFG)
            .headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml")
            .body(body)
        .when().post("/api/platform/connectors/apply")
        .then()
            .statusCode(200)
            .body("connectorId", equalTo(connectorId))
            .body("endpointsApplied", equalTo(1));
    }

    @Test
    void apply_returns_400_when_name_missing() {
        given()
            .config(YAML_CFG)
            .headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml")
            .body("apiVersion: connectors.lumnik.io/v1\nkind: RestConnector\nmetadata:\n  tenant: 1\nspec:\n  base_url: https://x.com\n")
        .when().post("/api/platform/connectors/apply")
        .then()
            .statusCode(400);
    }

    /** Tenant comes ONLY from the auth-resolved context — no tenant → 401, before validation. */
    @Test
    void apply_without_tenant_in_context_returns401() {
        given()
            .config(YAML_CFG)
            .contentType("application/yaml")
            .body(YAML_BODY)
        .when().post("/api/platform/connectors/apply")
        .then()
            .statusCode(401);
    }

    /**
     * A manifest may still declare metadata.tenant (documented in the connector docs), but it
     * can no longer CHOOSE the tenant: it must match the authenticated one or be refused —
     * never silently applied into someone else's tenant.
     */
    @Test
    void apply_refuses_manifest_whose_tenant_differs_from_context() {
        given()
            .config(YAML_CFG)
            .headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml")
            .body(YAML_BODY.replace("tenant: 1", "tenant: 2"))
        .when().post("/api/platform/connectors/apply")
        .then()
            .statusCode(400)
            .body("error", containsString("tenant"));
    }
}
