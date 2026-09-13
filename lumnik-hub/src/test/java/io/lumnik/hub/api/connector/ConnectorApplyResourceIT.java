package io.lumnik.hub.api.connector;

import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class ConnectorApplyResourceIT {

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
            .config(YamlBody.CONFIG)
            .headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml")
            .body(YAML_BODY)
        .when().post("/api/platform/connectors/apply")
        .then()
            .statusCode(200)
            .body("name", startsWith("test-apply-"))
            .body("endpointsApplied", equalTo(1))
            .body("connectorId", notNullValue())
            // …and says nothing when there is nothing to advise: the key is ABSENT, not an empty
            // list, so the one-line JSON body the connector docs print stays what it always was.
            .body("$", not(hasKey("warnings")));
    }

    /**
     * A successful apply carries the validator's advisories. The resource computed the
     * ValidationResult, refused on errors — and then dropped {@code warnings} on the floor: an
     * integrator on the documented one-command path was never told that a scopeless connector is
     * askable in no métier, while one who ran {@code lm validate} first was (#531).
     */
    @Test
    void apply_carries_validator_warnings_on_the_success_path() {
        String body = YAML_BODY
            .replace("test-apply-", "warned-apply-")
            .replace("  scopes: [crm, test]\n", "");

        given()
            .config(YamlBody.CONFIG)
            .headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml")
            .body(body)
        .when().post("/api/platform/connectors/apply")
        .then()
            .statusCode(200)
            .body("warnings.size()", equalTo(1))
            .body("warnings[0].path", equalTo("metadata.scopes"))
            .body("warnings[0].severity", equalTo("WARNING"))
            .body("warnings[0].message", containsString("not be askable"))
            .body("warnings[0].recommendations", not(empty()));
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
            .config(YamlBody.CONFIG)
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
            .config(YamlBody.CONFIG)
            .headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml")
            .body(body)
        .when().post("/api/platform/connectors/apply")
        .then()
            .statusCode(200)
            .body("connectorId", equalTo(connectorId))
            .body("endpointsApplied", equalTo(1));
    }

    /**
     * `lm endpoint delete` is a SOFT delete (EndpointResource.delete → softDelete()), and the
     * upsert that re-creates it looks the name up live (ConnectorEndpointRepository.findByName
     * filters deletedAt is null) — so re-applying the manifest builds a NEW row carrying the same
     * (connector_id, name). UNIQUE (connector_id, name) counted the tombstone: the apply died at
     * commit with 23505 and the integrator got an anonymous 500 for a manifest that had just
     * worked. The endpoint could never come back under its own name.
     */
    @Test
    void apply_puts_back_an_endpoint_that_was_deleted() {
        String body = YAML_BODY.replace("test-apply-", "readd-endpoint-");

        String connectorId = given()
            .config(YamlBody.CONFIG)
            .headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml")
            .body(body)
        .when().post("/api/platform/connectors/apply")
        .then().statusCode(200).extract().path("connectorId");

        String endpointId = given()
            .headers(TestIdentity.tenantAndUser(1))
        .when().get("/api/platform/connectors/" + connectorId + "/endpoints")
        .then().statusCode(200)
            .extract().path("find { it.name == 'contacts' }.id");

        given()
            .headers(TestIdentity.tenantAndUser(1))
        .when().delete("/api/platform/connectors/" + connectorId + "/endpoints/" + endpointId)
        .then().statusCode(204);

        given()
            .config(YamlBody.CONFIG)
            .headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml")
            .body(body)
        .when().post("/api/platform/connectors/apply")
        .then()
            .statusCode(200)
            .body("connectorId", equalTo(connectorId))
            .body("endpointsApplied", equalTo(1));

        // Back, and back ONCE: the endpoint the connector runs is the new live row, not a pair
        // of rows sharing a name with nothing to tell a reader which one a run would read.
        given()
            .headers(TestIdentity.tenantAndUser(1))
        .when().get("/api/platform/connectors/" + connectorId + "/endpoints")
        .then().statusCode(200)
            .body("findAll { it.name == 'contacts' }.size()", equalTo(1));
    }

    @Test
    void apply_returns_400_when_name_missing() {
        given()
            .config(YamlBody.CONFIG)
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
            .config(YamlBody.CONFIG)
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
            .config(YamlBody.CONFIG)
            .headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml")
            .body(YAML_BODY.replace("tenant: 1", "tenant: 2"))
        .when().post("/api/platform/connectors/apply")
        .then()
            .statusCode(400)
            .body("error", containsString("tenant"));
    }
}
