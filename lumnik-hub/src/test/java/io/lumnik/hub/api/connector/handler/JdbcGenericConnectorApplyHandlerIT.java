package io.lumnik.hub.api.connector.handler;

import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class JdbcGenericConnectorApplyHandlerIT {

    private static final RestAssuredConfig YAML_CFG = RestAssuredConfig.config()
        .encoderConfig(EncoderConfig.encoderConfig()
            .encodeContentTypeAs("application/yaml", ContentType.TEXT));

    @Test void apply_jdbcMultiTable_creates_connector_and_N_endpoints() {
        String yaml = """
            apiVersion: connectors.lumnik.io/v1
            kind: Connector
            metadata:
              name: jdbc-apply-%d
              connector_type: jdbc-generic
              tenant: 1
              scopes: [erp, billing]
            spec:
              connection:
                url: jdbc:postgresql://db.acme.io:5432/billing
                user: ro_user
                password_env: ACME_DB_PASSWORD
              defaults:
                schema: public
              source:
                tables:
                  - name: invoices
                    mode: watermark
                    watermark_column: updated_at
                    pk_columns: [id]
                  - name: customers
                    mode: full
                    pk_columns: [id]
                  - name: line_items
                    mode: watermark
                    watermark_column: ts
                    pk_columns: [invoice_id, line_no]
              schedule: "*/15 * * * *"
              target: {}
            """.formatted(System.nanoTime());

        String connectorId = given().config(YAML_CFG)
            .headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml")
            .body(yaml)
        .when().post("/api/platform/connectors/apply")
        .then()
            .statusCode(200)
            .body("name", startsWith("jdbc-apply-"))
            .body("endpointsApplied", equalTo(3))
            .body("connectorId", notNullValue())
            .extract().path("connectorId");

        // A10: the manifest cron must land on the Connector.schedule COLUMN (the
        // scheduler's WHERE clause) — not only in the config JSON.
        given()
            .headers(TestIdentity.tenantAndUser(1))
        .when().get("/api/platform/connectors/" + connectorId)
        .then()
            .statusCode(200)
            .body("schedule", equalTo("*/15 * * * *"));
    }
}
