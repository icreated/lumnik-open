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
class CsvFileConnectorApplyHandlerIT {

    private static final RestAssuredConfig YAML_CFG = RestAssuredConfig.config()
        .encoderConfig(EncoderConfig.encoderConfig()
            .encodeContentTypeAs("application/yaml", ContentType.TEXT));

    @Test void apply_csvSftp_creates_connector_and_one_endpoint() {
        String yaml = """
            apiVersion: connectors.lumnik.io/v1
            kind: Connector
            metadata:
              name: csv-apply-%d
              connector_type: csv-file
              tenant: 1
              scopes: [crm, billing]
            spec:
              transport:
                kind: sftp
                host: sftp.acme.io
                user: lumnik
                path: /export/*.csv
                key_env: ACME_SFTP_KEY
              parser:
                has_header: true
              schedule: "0 6 * * *"
              after_process: archive
              target: {}
            """.formatted(System.nanoTime());

        String connectorId = given().config(YAML_CFG)
            .headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml")
            .body(yaml)
        .when().post("/api/platform/connectors/apply")
        .then()
            .statusCode(200)
            .body("name", startsWith("csv-apply-"))
            .body("endpointsApplied", equalTo(1))
            .body("connectorId", notNullValue())
            .extract().path("connectorId");

        // A10: the manifest cron must land on the Connector.schedule COLUMN — the
        // scheduler selects on it (WHERE c.schedule IS NOT NULL); config-only storage
        // meant an applied manifest never scheduled a single run.
        given()
            .headers(TestIdentity.tenantAndUser(1))
        .when().get("/api/platform/connectors/" + connectorId)
        .then()
            .statusCode(200)
            .body("schedule", equalTo("0 6 * * *"));
    }
}
