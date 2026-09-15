package io.lumnik.hub.api.connector.handler;

import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class CsvFileConnectorApplyHandlerIT {

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

        String connectorId = given().config(YamlBody.CONFIG)
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

    /**
     * The manifest that accepts every CSV default writes neither block. Two things must hold, and
     * the second is why this is an IT: the apply must be accepted at all, and the omitted block
     * must leave NO key behind — {@code JSON.convertValue} turns a missing node into a null, and a
     * config carrying {@code parser: null} reads back through {@code lm connector get} as a block
     * the integrator never wrote. The runtime survives it ({@code CsvFileConnectorType} falls back
     * to {@code Map.of()} for anything that is not a Map), so nothing else would ever say it.
     */
    @Test void apply_csvWithoutParser_storesNoParserKey() {
        String yaml = """
            apiVersion: connectors.lumnik.io/v1
            kind: Connector
            metadata:
              name: csv-defaults-%d
              connector_type: csv-file
              tenant: 1
              scopes: [crm]
            spec:
              transport:
                kind: local
                path: /export/*.csv
              after_process: none
            """.formatted(System.nanoTime());

        String connectorId = given().config(YamlBody.CONFIG)
            .headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml")
            .body(yaml)
        .when().post("/api/platform/connectors/apply")
        .then()
            .statusCode(200)
            .body("endpointsApplied", equalTo(1))
            .extract().path("connectorId");

        configHasNoKey(connectorId, "parser");
    }

    /**
     * The twin door. `parser:` written with nothing after it is a NullNode, not a missing one, and
     * the validator agrees it is absent ({@code ConfigDefRunner} reads present as non-null AND
     * non-missing) — so it passes clean and reaches the handler, where an isMissingNode()-only
     * guard would wave it through into the same stored {@code parser: null} the guard exists to
     * prevent. Closing one door and leaving its twin open is the shape this repo keeps paying for.
     */
    @Test void apply_csvWithExplicitlyNullParser_storesNoParserKey() {
        String connectorId = applyCsv("csv-nullparser-%d", "  parser:\n");

        configHasNoKey(connectorId, "parser");
    }

    /**
     * Omitting the block must MEAN something. Apply upserts by name and {@code buildConnectorConfig}
     * seeds its map from the connector's existing config, so a guard that only ever puts leaves
     * yesterday's value in place: declare {@code delimiter: ";"}, then delete the block expecting
     * the documented auto-detection, and the run keeps parsing on semicolons in silence. This was
     * unreachable while the block was required — always present meant always overwritten — so
     * making it optional is what opens it, and closing it is part of the same change.
     */
    @Test void reapplyWithoutParser_dropsThePreviouslyAppliedBlock() {
        String name = "csv-reapply-%d".formatted(System.nanoTime());
        String withParser = applyCsvNamed(name, "  parser:\n    delimiter: \";\"\n");

        given()
            .headers(TestIdentity.tenantAndUser(1))
        .when().get("/api/platform/connectors/" + withParser)
        .then().statusCode(200).body("config.parser.delimiter", equalTo(";"));

        String reapplied = applyCsvNamed(name, "");

        configHasNoKey(reapplied, "parser");
    }

    /**
     * `indexes` is the same door, one block over: it is read out of `spec.target`, which this change
     * also made omittable — so deleting the block a manifest is now invited to delete would have kept
     * yesterday's index declaration alive. Closed for the same reason and in the same gesture.
     *
     * <p>Note what this does NOT claim: removing the declaration does not DROP the Postgres index
     * already created. It stops being declared; the index stays until the table does.
     */
    @Test void reapplyWithoutTarget_dropsThePreviouslyAppliedIndexes() {
        String name = "csv-reindex-%d".formatted(System.nanoTime());
        String declared = applyCsvNamed(name, "  target:\n    indexes:\n      - columns: [email]\n");

        given()
            .headers(TestIdentity.tenantAndUser(1))
        .when().get("/api/platform/connectors/" + declared)
        .then().statusCode(200).body("config.indexes[0].columns[0]", equalTo("email"));

        String reapplied = applyCsvNamed(name, "");

        configHasNoKey(reapplied, "indexes");
    }

    /**
     * Third door of the same family, and the one I left open while closing its neighbour: `has()`
     * answers TRUE for a key whose value is null, so `indexes:` written empty walked past the guard
     * three lines under the one that refuses exactly that for `parser`. Checking a key's PRESENCE is
     * not checking its VALUE — and the validator, which reads absent as null-OR-missing, had already
     * waved the manifest through.
     */
    @Test void apply_csvWithExplicitlyNullIndexes_storesNoIndexesKey() {
        String connectorId = applyCsv("csv-nullindex-%d", "  target:\n    indexes:\n");

        configHasNoKey(connectorId, "indexes");
    }

    /**
     * The same family's nastiest member, because its trap is not a null but a STRING. `.asText()` on
     * a NullNode returns the literal "null", so an `archive_path:` written empty was stored as a
     * path named "null" — and with `after_process: move` the connector would then move processed
     * files into a directory called that. The validator now refuses that combination outright
     * (moveWithoutAnArchivePath_isRefused); this pins the other half, where after_process makes the
     * path irrelevant and nothing would ever have noticed the string.
     */
    @Test void apply_csvWithExplicitlyNullArchivePath_storesNoArchivePathKey() {
        String connectorId = applyCsv("csv-nullarchive-%d", "  archive_path:\n");

        configHasNoKey(connectorId, "archive_path");
    }

    /**
     * The third block on this handler, left standing when #545 closed its two neighbours (#551).
     * `mapping` is where the cell and row hooks live, so a stored `mapping: null` is a block the
     * integrator never wrote sitting in front of the one place a reader looks for a transform.
     */
    @Test void apply_csvWithExplicitlyNullMapping_storesNoMappingKey() {
        String connectorId = applyCsv("csv-nullmapping-%d", "  mapping:\n");

        configHasNoKey(connectorId, "mapping");
    }

    /**
     * And its omission half: deleting the transform is the gesture that says "ingest the column
     * raw again". A guard that only puts keeps yesterday's hooks running against a manifest that
     * no longer declares a single one.
     */
    @Test void reapplyWithoutMapping_dropsThePreviouslyAppliedBlock() {
        String name = "csv-remapping-%d".formatted(System.nanoTime());
        String withMapping = applyCsvNamed(name,
            "  mapping:\n    dataset:\n      - skip-empty-rows: {}\n");

        given()
            .headers(TestIdentity.tenantAndUser(1))
        .when().get("/api/platform/connectors/" + withMapping)
        .then().statusCode(200).body("config.mapping.dataset[0]", hasKey("skip-empty-rows"));

        String reapplied = applyCsvNamed(name, "");

        configHasNoKey(reapplied, "mapping");
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

    private String applyCsv(String nameTemplate, String specBlock) {
        return applyCsvNamed(nameTemplate.formatted(System.nanoTime()), specBlock);
    }

    /** One local-transport csv manifest, varying only the spec block under test. */
    private String applyCsvNamed(String name, String specBlock) {
        String yaml = """
            apiVersion: connectors.lumnik.io/v1
            kind: Connector
            metadata:
              name: %s
              connector_type: csv-file
              tenant: 1
              scopes: [crm]
            spec:
              transport:
                kind: local
                path: /export/*.csv
            %s  after_process: none
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
