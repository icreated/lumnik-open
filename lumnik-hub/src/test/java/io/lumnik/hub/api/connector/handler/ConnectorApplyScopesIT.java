package io.lumnik.hub.api.connector.handler;

import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.config.RestAssuredConfig;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;

/**
 * What lands on the connector is exactly {@code metadata.scopes} — for every kind, through the
 * one seam ({@code ApplyUpsert}).
 *
 * <p>Nothing pinned this before. Three apply ITs put tags in both homes and asserted only that
 * the connector was created; the merge that unioned them ({@code TagsMerger}) had no test of its
 * own. So the two homes drifted unobserved: for REST, {@code target} is per endpoint and the merge
 * read the root — an endpoint's tags were validated, stored in its config, and never reached the
 * connector. The read-back is the JSON field {@code tags}: the wire name stays, the manifest
 * word changed.
 */
@QuarkusTest
class ConnectorApplyScopesIT {

    private static final RestAssuredConfig YAML_CFG = RestAssuredConfig.config()
        .encoderConfig(EncoderConfig.encoderConfig()
            .encodeContentTypeAs("application/yaml", ContentType.TEXT));

    private static String apply(String yaml) {
        return given().config(YAML_CFG)
            .headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml").body(yaml)
        .when().post("/api/platform/connectors/apply")
        .then().statusCode(200)
            .extract().path("connectorId");
    }

    @Test
    void csv_scopes_land_on_the_connector_in_declared_order() {
        String id = apply("""
            apiVersion: connectors.lumnik.io/v1
            kind: Connector
            metadata:
              name: scopes-csv-%d
              connector_type: csv-file
              scopes: [crm, billing]
            spec:
              transport: { kind: local, path: /imports/*.csv }
              parser: { has_header: true }
              after_process: none
              target: {}
            """.formatted(System.nanoTime()));

        given().headers(TestIdentity.tenantAndUser(1))
        .when().get("/api/platform/connectors/" + id)
        .then().statusCode(200)
            .body("tags", contains("crm", "billing"));
    }

    @Test
    void rest_scopes_land_on_the_connector_with_nothing_read_from_endpoints() {
        String id = apply("""
            apiVersion: connectors.lumnik.io/v1
            kind: Connector
            metadata:
              name: scopes-rest-%d
              connector_type: rest-generic
              scopes: [sales]
            spec:
              base_url: https://api.example.com
              auth: { kind: bearer, token_env: T }
              endpoints:
                - id: contacts
                  path: /v1/contacts
                  method: GET
                  pagination: { kind: offset, offset_param: offset, limit_param: limit, limit: 100, total_path: $.total, response_path: $.data }
                  target: { kind: hybrid, table: ext.contacts, unique_key: "$.id" }
            """.formatted(System.nanoTime()));

        given().headers(TestIdentity.tenantAndUser(1))
        .when().get("/api/platform/connectors/" + id)
        .then().statusCode(200)
            .body("tags", contains("sales"));
    }
}
