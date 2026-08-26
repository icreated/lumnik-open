package io.lumnik.hub.api.connector;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class ConnectorApplyValidationTest {

    private static final RestAssuredConfig YAML_CFG = RestAssuredConfig.config()
        .encoderConfig(EncoderConfig.encoderConfig()
            .encodeContentTypeAs("application/yaml", io.restassured.http.ContentType.TEXT));

    @Test void applyInvalidManifest_returns400_withValidationResult() {
        String yaml = """
            apiVersion: connectors.lumnik.io/v1
            kind: Connector
            metadata:
              name: bad
              connector_type: rest-generic
            spec:
              base_url: https://api.example.com
              auth: { kind: bearer }
              endpoints:
                - { id: x, path: /x, method: GET }
              target: { table_prefix: x_, tags: [x] }
            """;
        given().config(YAML_CFG).contentType("application/yaml").body(yaml)
            .header("X-Tenant-Id", "1")   // tenant now required in context before validation
            .when().post("/api/platform/connectors/apply")
            .then().statusCode(400)
                  .body("errors.path", hasItem("spec.auth.token_env"));
    }
}
