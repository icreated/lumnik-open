package io.lumnik.hub.api.connector;

import io.lumnik.hub.testsupport.YamlBody;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class ConnectorApplyValidationTest {

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
              target: { table_prefix: x_ }
            """;
        given().config(YamlBody.CONFIG).contentType("application/yaml").body(yaml)
            .header("X-Tenant-Id", "1")   // tenant now required in context before validation
            .when().post("/api/platform/connectors/apply")
            .then().statusCode(400)
                  .body("errors.path", hasItem("spec.auth.token_env"));
    }

    /**
     * The 500 this closure removes, pinned at the door rather than at the tier. Measured before
     * the change: `auth: bearer` validated clean — `validateAuth` returns early on a non-object —
     * and this endpoint answered **500**, a Jackson `IllegalArgumentException` raised by
     * `RestConnectorApplyHandler`'s `convertValue(..., Map.class)`. Only three exception mappers
     * exist and none covers it. Declaring `auth` as a block gives the runner the type check, so the
     * refusal lands here as a named 400 — the same lesson as #469: the tier that is supposed to
     * refuse must be the one that does.
     */
    @Test void applyRestAuthThatIsNotABlock_returns400_notAJacksonFailure() {
        String yaml = """
            apiVersion: connectors.lumnik.io/v1
            kind: Connector
            metadata:
              name: auth-not-a-block
              connector_type: rest-generic
              scopes: [crm]
            spec:
              base_url: https://api.example.com
              auth: bearer
              endpoints:
                - id: x
                  path: /x
                  method: GET
                  pagination: { kind: offset, offset_param: offset, limit_param: limit, size: 50, response_path: "$.data" }
                  target: { kind: hybrid, table: ext.x, unique_key: "$.id" }
            """;
        // The ONLY defect in this manifest is auth's shape, so the refusal cannot be credited to
        // something else: exactly one error, on exactly that path.
        given().config(YamlBody.CONFIG).contentType("application/yaml").body(yaml)
            .header("X-Tenant-Id", "1")
            .when().post("/api/platform/connectors/apply")
            .then().statusCode(400)
                  .body("errors.size()", equalTo(1))
                  .body("errors[0].path", equalTo("spec.auth"));
    }

    @Test void applyJdbcTableNameThatCannotBecomeAHubTable_isRefusedByTheDeclarativeValidator() {
        // Why this door is the one that matters: see SourceBlockValidator.perTableDef(). The refusal
        // lands at the declarative tier, which ConnectorApplyResource runs BEFORE it dispatches to the
        // handler — so nothing persists, though that ordering is what this test reads, not an assertion
        // it makes.
        String yaml = """
            apiVersion: connectors.lumnik.io/v1
            kind: Connector
            metadata:
              name: jdbc-bad-table
              connector_type: jdbc-generic
            spec:
              connection:
                url: jdbc:postgresql://db/erp
                user: reader
                password_env: PGPASSWORD
              source:
                tables:
                  - name: 9orders
                    mode: full
                    pk_columns: [id]
            """;
        given().config(YamlBody.CONFIG).contentType("application/yaml").body(yaml)
            .header("X-Tenant-Id", "1")
            .when().post("/api/platform/connectors/apply")
            .then().statusCode(400)
                  .body("errors.path", hasItem("spec.source.tables[0].name"));
    }

    // The kafka twin of the test above lives in KafkaConnectorApplyResourceIT, not here, and must
    // stay there: kafka is closed-edition only. This class sits in an OPEN package, so it survives
    // extract-open-edition.sh while the pro tree is pruned by path — a kafka manifest posted from
    // here would reach a deployment with no kafka validator and turn proof 1/5 of the publish
    // ritual red, twenty minutes in.
    //
    // check-bounded-contexts.sh 1b could not have caught that: it scans test sources for DOTTED
    // references to the closed packages, and a connector_type is a bare string in a text block.
    // Spelling that package name out here would itself trip 1b — which is how this comment came to
    // describe the rule without naming it, and is the sharpest proof of what 1b does and does not
    // see. The rule that does hold is LOCATION: a test that exercises a closed type lives under the
    // pro tree, or it outlives its subject.
}
