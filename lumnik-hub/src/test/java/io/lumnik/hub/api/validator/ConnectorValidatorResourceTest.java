package io.lumnik.hub.api.validator;

import io.lumnik.hub.testsupport.YamlBody;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.config;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class ConnectorValidatorResourceTest {

    @Test void validate_validYaml_returnsValid() {
        // The runtime contract: auth optional (connector-level), pagination + target per ENDPOINT.
        String yaml = """
            apiVersion: connectors.lumnik.io/v1
            kind: Connector
            metadata:
              name: x
              connector_type: rest-generic
              scopes: [x]
            spec:
              base_url: https://api.example.com
              auth: { kind: bearer, token_env: T }
              endpoints:
                - id: x
                  path: /x
                  method: GET
                  pagination: { kind: cursor, cursor_param: after, cursor_path: "$.data[-1:].id", has_more_path: "$.has_more", response_path: "$.data" }
                  target: { kind: hybrid, table: ext.x, unique_key: "$.id", promote: [{ name: n, path: "$.n", type: text }] }
            """;
        given()
            .config(config().encoderConfig(
                EncoderConfig.encoderConfig().encodeContentTypeAs("application/x-yaml", ContentType.TEXT)))
            .contentType("application/x-yaml").body(yaml)
            .when().post("/api/platform/validator/validate")
            .then().statusCode(200)
                  .body("errors", empty())
                  .body("warnings", empty());
    }

    @Test void validate_acceptsStandardApplicationYaml() {
        // The lm CLI sends Content-Type: application/yaml (the RFC 9512 / IANA-standard YAML type).
        // The endpoint must accept it, not 415.
        String yaml = """
            apiVersion: connectors.lumnik.io/v1
            kind: Connector
            metadata:
              name: x
              connector_type: rest-generic
              scopes: [x]
            spec:
              base_url: https://api.example.com
              auth: { kind: bearer, token_env: T }
              endpoints:
                - id: x
                  path: /x
                  method: GET
                  pagination: { kind: cursor, cursor_param: after, cursor_path: "$.data[-1:].id", has_more_path: "$.has_more", response_path: "$.data" }
                  target: { kind: hybrid, table: ext.x, unique_key: "$.id" }
            """;
        given()
            .config(config().encoderConfig(
                EncoderConfig.encoderConfig().encodeContentTypeAs("application/yaml", ContentType.TEXT)))
            .contentType("application/yaml").body(yaml)
            .when().post("/api/platform/validator/validate")
            .then().statusCode(200)
                  .body("errors", empty());
    }

    /** A media type is case-insensitive: `Application/YAML` is read as YAML, not as JSON that fails to parse. */
    @Test void validate_readsYamlWhateverTheCaseOfItsMediaType() {
        given()
            .config(config().encoderConfig(
                EncoderConfig.encoderConfig().encodeContentTypeAs("Application/YAML", ContentType.TEXT)))
            .contentType("Application/YAML").body("apiVersion: connectors.lumnik.io/v1\nkind: Connector\n")
            .when().post("/api/platform/validator/validate")
            .then().statusCode(200)
                  .body("errors.message", not(hasItem(startsWith("could not parse manifest"))));
    }

    /** #1489: a scope named on/no is text on this door too — it read YAML 1.1, where both are booleans. */
    @Test void validate_readsYaml11BooleanWordsAsText() {
        String yaml = """
            apiVersion: connectors.lumnik.io/v1
            kind: Connector
            metadata:
              name: x
              connector_type: rest-generic
              scopes: [on, no]
            spec:
              base_url: https://api.example.com
              auth: { kind: bearer, token_env: T }
              endpoints:
                - id: x
                  path: /x
                  method: GET
                  pagination: { kind: cursor, cursor_param: after, cursor_path: "$.data[-1:].id", has_more_path: "$.has_more", response_path: "$.data" }
                  target: { kind: hybrid, table: ext.x, unique_key: "$.id" }
            """;
        given().config(YamlBody.CONFIG).contentType("application/yaml").body(yaml)
            .when().post("/api/platform/validator/validate")
            .then().statusCode(200)
                  .body("errors", empty());
    }

    @Test void describe_knownKind_returnsConfigDef() {
        given().when().get("/api/platform/validator/describe/rest-generic")
            .then().statusCode(200)
                  .body("fields.name", hasItems("base_url", "endpoints"));   // auth is optional (cross-field), not a top-level configDef field
    }

    @Test void describe_unknownKind_returns404() {
        given().when().get("/api/platform/validator/describe/nope")
            .then().statusCode(404)
                  .body("available", hasItem("rest-generic"));
    }
}
