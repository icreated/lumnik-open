package io.lumnik.hub.api.connector.handler;

import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * The csv door's family, two connector types over (#551) — {@code CsvFileConnectorApplyHandlerIT}
 * states both halves in full and this file does not restate them. What is rest-specific is on the
 * cases below.
 */
@QuarkusTest
class RestConnectorApplyHandlerIT {

    @Test void apply_restWithExplicitlyNullAuth_storesNoAuthKey() {
        String connectorId = applyRest("rest-nullauth-%d", "  auth:\n");

        configHasNoKey(connectorId, "auth");
    }

    @Test void apply_restWithExplicitlyNullHeaders_storesNoHeadersKey() {
        String connectorId = applyRest("rest-nullheaders-%d", "  headers:\n");

        configHasNoKey(connectorId, "headers");
    }

    /**
     * Deleting {@code auth} is how a manifest says "this API stopped needing a token" — the
     * validator's own wording for the omission is "omit =&gt; none at runtime". Without the
     * removal the connector keeps presenting yesterday's bearer token to an endpoint the
     * integrator just declared open.
     */
    @Test void reapplyWithoutAuth_dropsThePreviouslyAppliedBlock() {
        String name = "rest-reauth-%d".formatted(System.nanoTime());
        String withAuth = applyRestNamed(name, "  auth:\n    kind: bearer\n    token_env: ACME_API_KEY\n");

        given()
            .headers(TestIdentity.tenantAndUser(1))
        .when().get("/api/platform/connectors/" + withAuth)
        .then().statusCode(200).body("config.auth.kind", equalTo("bearer"));

        String reapplied = applyRestNamed(name, "");

        configHasNoKey(reapplied, "auth");
    }

    @Test void reapplyWithoutHeaders_dropsThePreviouslyAppliedBlock() {
        String name = "rest-reheaders-%d".formatted(System.nanoTime());
        String withHeaders = applyRestNamed(name,
            "  headers:\n    - name: X-Acme-Tenant\n      value: acme\n");

        given()
            .headers(TestIdentity.tenantAndUser(1))
        .when().get("/api/platform/connectors/" + withHeaders)
        .then().statusCode(200).body("config.headers[0].name", equalTo("X-Acme-Tenant"));

        String reapplied = applyRestNamed(name, "");

        configHasNoKey(reapplied, "headers");
    }


    /** The read-back every case here ends on: what `lm connector get` would show. */
    private void configHasNoKey(String connectorId, String key) {
        given()
            .headers(TestIdentity.tenantAndUser(1))
        .when().get("/api/platform/connectors/" + connectorId)
        .then()
            .statusCode(200)
            .body("config", not(hasKey(key)));
    }

    /**
     * The endpoint door: {@code upsertEndpoint} flattens every endpoint field with
     * {@code convertValue(..., Object.class)}, which keeps a NullNode as a null entry — kafka's
     * mechanism, one level down. It matters more here than at the connector level, because
     * {@code EffectiveConfig.of(connector, endpoint)} lays the endpoint config OVER the connector's
     * with {@code putAll}: a null endpoint entry does not merely sit there, it ERASES the
     * connector-level value of the same name at run time.
     */
    @Test void anEndpointKeyWrittenEmpty_storesNoKeyOnTheEndpoint() {
        String connectorId = applyRest("rest-emptyepkey-%d", "");

        given()
            .headers(TestIdentity.tenantAndUser(1))
        .when().get("/api/platform/connectors/" + connectorId + "/endpoints")
        .then()
            .statusCode(200)
            .body("[0].config", not(hasKey("incremental")));
    }

    private String applyRest(String nameTemplate, String specBlock) {
        return applyRestNamed(nameTemplate.formatted(System.nanoTime()), specBlock);
    }

    /** One rest manifest with one endpoint, varying only the spec block under test. */
    private String applyRestNamed(String name, String specBlock) {
        String yaml = """
            apiVersion: connectors.lumnik.io/v1
            kind: Connector
            metadata:
              name: %s
              connector_type: rest-generic
              tenant: 1
              scopes: [crm]
            spec:
              base_url: https://api.example.com
            %s  endpoints:
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
                  incremental:
            """.formatted(name, specBlock);

        return given().config(YamlBody.CONFIG)
            .headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml")
            .body(yaml)
        .when().post("/api/platform/connectors/apply")
        .then()
            .statusCode(200)
            .extract().path("connectorId");
    }
}
