package io.lumnik.hub.api.connector;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.audit.AuditRecorder;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1193: every door that writes a connector leaves a row in the audit trail, keyed by the
 * connector's id — the manifest apply (with the manifest's digest), the flat create/update/delete,
 * the scope (tags) door and the CSV one-shot. The jdbc guided add is pinned in
 * {@code JdbcSourceAddHonestyIT}, which owns a source database.
 */
@QuarkusTest
class ConnectorWriteAuditIT {

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @Test
    void aManifestApplyIsRecordedWithTheManifestDigest() {
        String name = "audit-apply-" + System.nanoTime();
        String manifest = """
            apiVersion: connectors.lumnik.io/v1
            kind: Connector
            metadata:
              name: %s
              connector_type: rest-generic
              scopes: [crm]
            spec:
              base_url: https://api.example.com
              auth:
                kind: bearer
                token_env: EXAMPLE_API_KEY
              endpoints:
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
                    table: ext.audit_contacts
                    unique_key: "$.id"
                    promote:
                      - { name: name, path: "$.name", type: text }
            """.formatted(name);

        String id = given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml").body(manifest)
            .post("/api/platform/connectors/apply")
            .then().statusCode(200).extract().path("connectorId");

        assertThat(rows(id, "APPLY")).isEqualTo(1);
        assertThat(changes(id, "APPLY")).contains(AuditRecorder.sha256(manifest)).contains(name);
    }

    @Test
    void theFlatDoorRecordsCreateUpdateScopeChangeAndDelete() {
        String name = "audit-flat-" + System.nanoTime();
        Map<String, Object> body = new HashMap<>(Map.of(
            "tenantId", 1, "name", name, "typeId", "hello-world", "enabled", false,
            "config", Map.of("count", 1), "tags", List.of("crm")));

        String id = given().headers(TestIdentity.tenantAndUser(1)).contentType(ContentType.JSON).body(body)
            .post("/api/platform/connectors")
            .then().statusCode(201).extract().path("id");
        assertThat(rows(id, "APPLY")).as("create").isEqualTo(1);

        body.put("schedule", "0 3 * * *");
        given().headers(TestIdentity.tenantAndUser(1)).contentType(ContentType.JSON).body(body)
            .put("/api/platform/connectors/" + id)
            .then().statusCode(200);
        assertThat(rows(id, "APPLY")).as("update").isEqualTo(2);

        given().headers(TestIdentity.tenantAndUser(1)).contentType(ContentType.JSON).body(List.of("crm", "sales"))
            .put("/api/platform/connectors/" + id + "/tags")
            .then().statusCode(200);
        assertThat(rows(id, "APPLY")).as("scope change").isEqualTo(3);

        given().headers(TestIdentity.tenantAndUser(1))
            .delete("/api/platform/connectors/" + id)
            .then().statusCode(204);
        assertThat(rows(id, "DELETE")).isEqualTo(1);
        assertThat(changes(id, "DELETE")).contains(name);
    }

    @Test
    void aWriteThatFailsAtCommitLeavesNoRow() {
        String taken = "audit-taken-" + System.nanoTime();
        String other = "audit-other-" + System.nanoTime();
        given().headers(TestIdentity.tenantAndUser(1)).contentType(ContentType.JSON)
            .body(Map.of("tenantId", 1, "name", taken, "typeId", "hello-world", "enabled", false, "config", Map.of("count", 1)))
            .post("/api/platform/connectors")
            .then().statusCode(201);
        Map<String, Object> body = new HashMap<>(Map.of(
            "tenantId", 1, "name", other, "typeId", "hello-world", "enabled", false, "config", Map.of("count", 1)));
        String id = given().headers(TestIdentity.tenantAndUser(1)).contentType(ContentType.JSON).body(body)
            .post("/api/platform/connectors")
            .then().statusCode(201).extract().path("id");

        // The rename is flushed at commit, where the (tenant, name) unique key refuses it.
        body.put("name", taken);
        int status = given().headers(TestIdentity.tenantAndUser(1)).contentType(ContentType.JSON).body(body)
            .put("/api/platform/connectors/" + id)
            .then().extract().statusCode();

        assertThat(status).as("the rename onto a taken name is refused").isGreaterThanOrEqualTo(400);
        assertThat(rows(id, "APPLY")).as("only the create, never the refused rename").isEqualTo(1);
        assertThat(changes(id, "APPLY")).doesNotContain(taken);
    }

    @Test
    void theCsvOneShotRecordsTheConnectorItCreates() throws Exception {
        File csv = Files.createTempFile("audit_ingest_", ".csv").toFile();
        Files.writeString(csv.toPath(), "id,email\n1,a@x\n");
        try {
            String id = given().headers(TestIdentity.tenantAndUser(1))
                .multiPart("file", csv)
                .multiPart("name", "audit_csv_" + System.nanoTime())
                .multiPart("scope", "system")
                .post("/api/platform/sources/csv/ingest")
                .then().statusCode(200).extract().path("connectorId");
            assertThat(rows(id, "APPLY")).isEqualTo(1);
        } finally {
            csv.delete();
        }
    }

    private long rows(String connectorId, String operation) {
        return Jdbc.queryOne(adminDs, "ConnectorWriteAuditIT.rows",
            "SELECT count(*) FROM core.audit_outbox "
                + "WHERE tenant_id = 1 AND entity_type = 'Connector' AND entity_key = ? AND operation = ?",
            ps -> {
                ps.setString(1, connectorId);
                ps.setString(2, operation);
            },
            rs -> rs.getLong(1)).orElseThrow();
    }

    private String changes(String connectorId, String operation) {
        return Jdbc.queryOne(adminDs, "ConnectorWriteAuditIT.changes",
            "SELECT string_agg(changes::text, ' ') FROM core.audit_outbox "
                + "WHERE tenant_id = 1 AND entity_type = 'Connector' AND entity_key = ? AND operation = ?",
            ps -> {
                ps.setString(1, connectorId);
                ps.setString(2, operation);
            },
            rs -> rs.getString(1)).orElseThrow();
    }
}
