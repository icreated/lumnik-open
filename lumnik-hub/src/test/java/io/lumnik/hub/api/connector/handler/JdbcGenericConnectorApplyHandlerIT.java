package io.lumnik.hub.api.connector.handler;

import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class JdbcGenericConnectorApplyHandlerIT {

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
              window: "22:00-06:00"
            """.formatted(System.nanoTime());

        String connectorId = given().config(YamlBody.CONFIG)
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
        //
        // And the read door must GIVE BACK both preamble fields, each exactly once (#499).
        // `window` was stored on its own column and shown by nothing, so `lm connector list`
        // had a SCHED column and no fence and the flat `get -> edit -> apply` loop dropped a
        // declared window without a word. `schedule` had the opposite defect: the two handlers
        // that copy it into the config JSON made `lm connector get` print it twice, and the copy
        // is read by nobody — the scheduler selects on the column.
        given()
            .headers(TestIdentity.tenantAndUser(1))
        .when().get("/api/platform/connectors/" + connectorId)
        .then()
            .statusCode(200)
            .body("schedule", equalTo("*/15 * * * *"))
            .body("window", equalTo("22:00-06:00"))
            .body("config.schedule", nullValue());
    }
}
