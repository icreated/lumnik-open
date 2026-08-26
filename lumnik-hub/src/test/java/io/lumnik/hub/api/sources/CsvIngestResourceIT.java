package io.lumnik.hub.api.sources;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;

import static org.hamcrest.Matchers.*;

@QuarkusTest
class CsvIngestResourceIT {

    @Test
    void multipart_upload_ingests_rows() throws Exception {
        File csv = Files.createTempFile("it_ingest_", ".csv").toFile();
        Files.writeString(csv.toPath(),
            "id,email\n" +
            "1,a@x\n" +
            "2,b@x\n");

        RestAssured.given()
            .header("X-Tenant-Id", "1")
            .multiPart("file", csv)
            .multiPart("name", "it_test_" + System.nanoTime())
            .multiPart("scope", "system")
            .post("/api/platform/sources/csv/ingest")
            .then()
            .statusCode(200)
            .body("rowsWritten", equalTo(2))
            .body("tableName",   startsWith("ext."))
            .body("connectorId", notNullValue())
            .body("endpointId",  notNullValue());

        csv.delete();
    }

    /**
     * A11: the refusal has to reach the human. An IllegalArgumentException from the service is an
     * unmapped 500 whose message Quarkus withholds in prod — a refusal nobody can read teaches
     * nothing, so this endpoint answers 400 and says why.
     */
    @Test
    void a_refused_ingest_answers_400_with_the_reason() throws Exception {
        java.nio.file.Path dir = Files.createTempDirectory("it_a11_");
        Files.writeString(dir.resolve("a-" + System.nanoTime() + ".csv"), "id,email\n1,a@x\n");
        Files.writeString(dir.resolve("b-" + System.nanoTime() + ".csv"), "id,email\n2,b@x\n");

        RestAssured.given()
            .header("X-Tenant-Id", "1")
            .multiPart("transport", "{\"kind\":\"local\",\"path\":\"" + dir.toAbsolutePath() + "/*.csv\"}")
            .multiPart("name", "it_a11_" + System.nanoTime())
            .post("/api/platform/sources/csv/ingest")
            .then()
            .statusCode(400)
            .body("error", containsString("single file"))
            .body("error", containsString("manifest"));
    }

    /**
     * Task 6 regression: a refused {@code pre_process} hook {@code script_ref} must answer 400,
     * not 500, on the remote/FileSource ingest path — the same guarantee already proven for
     * {@code file_filter} refusals. {@code FileSourceHookRunner} throws a clean refusal for an
     * absolute {@code script_ref}, but {@code CsvIngestService.ingestRemoteFile} used to re-wrap
     * ANY {@code RuntimeException} escaping {@code bufferToTemp} (where {@code pre_process} runs)
     * into a fresh, untyped {@code RuntimeException} — destroying the type the resource's
     * {@code @ServerExceptionMapper} keys on, and turning the refusal into an unmapped 500.
     */
    @Test
    void a_refused_pre_process_hook_answers_400_not_500() throws Exception {
        java.nio.file.Path dir = Files.createTempDirectory("it_task6_");
        java.nio.file.Path csv = dir.resolve("data.csv");
        Files.writeString(csv, "id,email\n1,a@x\n");

        RestAssured.given()
            .header("X-Tenant-Id", "1")
            .multiPart("transport", "{\"kind\":\"local\",\"path\":\"" + csv.toAbsolutePath()
                + "\",\"pre_process\":{\"kind\":\"kotlin\",\"script_ref\":\"/tmp/task6-evil.kts\"}}")
            .multiPart("name", "it_task6_" + System.nanoTime())
            .post("/api/platform/sources/csv/ingest")
            .then()
            .statusCode(400)
            .body("error", not(containsString("/tmp/task6-evil.kts")));
    }

    /**
     * Contract change (was: defaults to tenant 1): tenant comes ONLY from the auth-resolved
     * context — a caller with no tenant must never ingest into someone's tenant by default.
     */
    @Test
    void ingest_without_tenant_in_context_returns401() throws Exception {
        File csv = Files.createTempFile("it_notenant_", ".csv").toFile();
        Files.writeString(csv.toPath(), "sku,qty\nABC,10\n");

        RestAssured.given()
            .multiPart("file", csv)
            .multiPart("name", "notenant_test_" + System.nanoTime())
            .post("/api/platform/sources/csv/ingest")
            .then()
            .statusCode(401);

        csv.delete();
    }
}
