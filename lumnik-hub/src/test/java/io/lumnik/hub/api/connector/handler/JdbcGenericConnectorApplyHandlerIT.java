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

    /**
     * The same two claims as the csv door, one connector type over (#551). {@code defaults} is the
     * per-table defaults block: stored under its own key and merged into every endpoint, so a
     * {@code defaults: null} is both a block the integrator never wrote and — on the omission half
     * — yesterday's schema still silently qualifying every table in today's manifest.
     */
    @Test void apply_jdbcWithExplicitlyNullDefaults_storesNoDefaultsKey() {
        String connectorId = applyJdbc("jdbc-nulldefaults-%d", "  defaults:\n");

        configHasNoKey(connectorId, "defaults");
    }

    @Test void reapplyWithoutDefaults_dropsThePreviouslyAppliedBlock() {
        String name = "jdbc-redefaults-%d".formatted(System.nanoTime());
        String withDefaults = applyJdbcNamed(name, "  defaults:\n    schema: legacy\n");

        given()
            .headers(TestIdentity.tenantAndUser(1))
        .when().get("/api/platform/connectors/" + withDefaults)
        .then().statusCode(200).body("config.defaults.schema", equalTo("legacy"));

        String reapplied = applyJdbcNamed(name, "");

        configHasNoKey(reapplied, "defaults");
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
     * The endpoint door of the same family, and the one where the null is not cosmetic: a table key
     * written empty is a NullNode, and {@code TableDefaultsMerger} skips it as absent, so
     * {@code defaults: {schema: legacy}} plus a table carrying {@code schema:} keeps the default the
     * manifest declares three lines above. Were the null copied over it instead, the merged table
     * would have no schema — refused since #1243, stored as null before it. This pins the kept default.
     */
    @Test void aTableKeyWrittenEmpty_keepsTheDeclaredDefault() {
        String name = "jdbc-emptytablekey-%d".formatted(System.nanoTime());
        String yaml = """
            apiVersion: connectors.lumnik.io/v1
            kind: Connector
            metadata:
              name: %s
              connector_type: jdbc-generic
              tenant: 1
              scopes: [erp]
            spec:
              connection:
                url: jdbc:postgresql://db.acme.io:5432/billing
                user: ro_user
                password_env: ACME_DB_PASSWORD
              defaults:
                schema: legacy
              source:
                tables:
                  - name: invoices
                    mode: full
                    pk_columns: [id]
                    schema:
            """.formatted(name);

        String connectorId = given().config(YamlBody.CONFIG)
            .headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml")
            .body(yaml)
        .when().post("/api/platform/connectors/apply")
        .then().statusCode(200).extract().path("connectorId");

        given()
            .headers(TestIdentity.tenantAndUser(1))
        .when().get("/api/platform/connectors/" + connectorId + "/endpoints")
        .then()
            .statusCode(200)
            .body("[0].config.schema", equalTo("legacy"));
    }

    private String applyJdbc(String nameTemplate, String specBlock) {
        return applyJdbcNamed(nameTemplate.formatted(System.nanoTime()), specBlock);
    }

    /** One jdbc manifest with one table, varying only the spec block under test. */
    private String applyJdbcNamed(String name, String specBlock) {
        // schema is required (#1243): a spec block that does not set it gets one on the table.
        String tableSchema = specBlock.contains("schema:") ? "" : "\n        schema: public";
        String yaml = """
            apiVersion: connectors.lumnik.io/v1
            kind: Connector
            metadata:
              name: %s
              connector_type: jdbc-generic
              tenant: 1
              scopes: [erp]
            spec:
              connection:
                url: jdbc:postgresql://db.acme.io:5432/billing
                user: ro_user
                password_env: ACME_DB_PASSWORD
            %s  source:
                tables:
                  - name: invoices
                    mode: full
                    pk_columns: [id]%s
            """.formatted(name, specBlock, tableSchema);

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
