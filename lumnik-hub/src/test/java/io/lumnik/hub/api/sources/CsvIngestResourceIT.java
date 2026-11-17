package io.lumnik.hub.api.sources;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.transport.TestDirFileSourceFactory;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class CsvIngestResourceIT {

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

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

    /** The upload door's limit is quarkus.http.limits.max-body-size (10M): under it lands, over it is a 413. */
    @ParameterizedTest
    @ValueSource(ints = {9, 11})
    void an_upload_over_the_body_limit_is_a_413(int megabytes) throws Exception {
        File csv = Files.createTempFile("it_size_", ".csv").toFile();
        Files.writeString(csv.toPath(), "id,pad\n" + ("1," + "x".repeat(1021) + "\n").repeat(megabytes * 1024));

        RestAssured.given()
            .header("X-Tenant-Id", "1")
            // The hub refuses on Content-Length and closes; without 100-continue the client is still writing.
            .header("Expect", "100-continue")
            .multiPart("file", csv)
            .multiPart("name", "it_size_" + System.nanoTime())
            .multiPart("scope", "system")
            .post("/api/platform/sources/csv/ingest")
            .then()
            .statusCode(megabytes < 10 ? 200 : 413);

        csv.delete();
    }

    /**
     * A malformed row is the installer's to fix, so it is a 400 naming the line (#1248) — never the
     * anonymous 500, and never the hub's upload temp path. Once inside the inference sample, once
     * past it (the full read).
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void a_malformed_row_is_a_400_naming_its_line(int sampleRows) throws Exception {
        File csv = Files.createTempFile("it_bad_", ".csv").toFile();
        Files.writeString(csv.toPath(), "id,x\n1,a\n2,b,c\n");

        var req = RestAssured.given()
            .header("X-Tenant-Id", "1")
            .multiPart("file", csv)
            .multiPart("name", "it_bad_" + System.nanoTime())
            .multiPart("scope", "system");
        if (sampleRows > 0) req = req.multiPart("sample_rows", String.valueOf(sampleRows));
        req.post("/api/platform/sources/csv/ingest")
            .then()
            .statusCode(400)
            .body("error", containsString("line 3"))
            .body("error", containsString("3 values but only 2 columns"))
            .body("error", not(containsString("upload")));

        csv.delete();
    }

    /**
     * A refusal past the first written batch lands nothing: the batches already flushed roll back
     * with the ingest, so the 400 never leaves a partial table or a dormant connector behind.
     */
    @Test
    void a_malformed_row_past_the_first_batch_lands_nothing() throws Exception {
        File csv = Files.createTempFile("it_late_", ".csv").toFile();
        StringBuilder body = new StringBuilder("id,x\n");
        for (int i = 1; i <= 600; i++) body.append(i).append(",a\n");
        body.append("601,b,c\n");
        Files.writeString(csv.toPath(), body);
        String name = "it_late_" + System.nanoTime();

        RestAssured.given()
            .header("X-Tenant-Id", "1")
            .multiPart("file", csv)
            .multiPart("name", name)
            .multiPart("scope", "system")
            .multiPart("sample_rows", "1")
            .post("/api/platform/sources/csv/ingest")
            .then()
            .statusCode(400)
            .body("error", containsString("line 602"));

        try (Connection c = adminDs.getConnection();
             PreparedStatement table = c.prepareStatement("SELECT to_regclass(?)");
             PreparedStatement connector = c.prepareStatement(
                 "SELECT count(*) FROM connector.connector WHERE tenant_id = 1 AND name = ?")) {
            table.setString(1, "ext." + name);
            connector.setString(1, name);
            try (ResultSet t = table.executeQuery(); ResultSet n = connector.executeQuery()) {
                t.next();
                n.next();
                assertNull(t.getString(1), "no table may survive the refusal");
                assertEquals(0, n.getInt(1), "no connector may survive it");
            }
        }
        csv.delete();
    }

    /**
     * The breath: re-ingesting the same one-shot name answers 200 with refreshed=true —
     * one more inspiration, never catch #8's 409 (which now guards only configured
     * connectors' names) and never the anonymous 500 the installer originally hit.
     */
    @Test
    void re_ingesting_the_same_name_answers_200_refreshed() throws Exception {
        File csv = Files.createTempFile("it_dup_", ".csv").toFile();
        Files.writeString(csv.toPath(), "id,email\n1,a@x\n");
        String name = "it_dup_" + System.nanoTime();

        RestAssured.given()
            .header("X-Tenant-Id", "1")
            .multiPart("file", csv)
            .multiPart("name", name)
            .multiPart("scope", "system")
            .post("/api/platform/sources/csv/ingest")
            .then().statusCode(200);

        RestAssured.given()
            .header("X-Tenant-Id", "1")
            .multiPart("file", csv)
            .multiPart("name", name)
            .multiPart("scope", "system")
            .post("/api/platform/sources/csv/ingest")
            .then()
            .statusCode(200)
            .body("refreshed", equalTo(true))
            .body("rowsWritten", equalTo(1));

        csv.delete();
    }

    /**
     * A11: the refusal has to reach the human. An IllegalArgumentException from the service is an
     * unmapped 500 whose message Quarkus withholds in prod — a refusal nobody can read teaches
     * nothing, so this endpoint answers 400 and says why.
     */
    @Test
    void a_refused_ingest_answers_400_with_the_reason() throws Exception {
        Path dir = Files.createTempDirectory("it_a11_");
        Files.writeString(dir.resolve("a-" + System.nanoTime() + ".csv"), "id,email\n1,a@x\n");
        Files.writeString(dir.resolve("b-" + System.nanoTime() + ".csv"), "id,email\n2,b@x\n");

        RestAssured.given()
            .header("X-Tenant-Id", "1")
            .multiPart("transport", "{\"kind\":\"" + TestDirFileSourceFactory.KIND + "\",\"path\":\"" + dir.toAbsolutePath() + "/*.csv\"}")
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
        Path dir = Files.createTempDirectory("it_task6_");
        Path csv = dir.resolve("data.csv");
        Files.writeString(csv, "id,email\n1,a@x\n");

        RestAssured.given()
            .header("X-Tenant-Id", "1")
            .multiPart("transport", "{\"kind\":\"" + TestDirFileSourceFactory.KIND + "\",\"path\":\"" + csv.toAbsolutePath()
                + "\",\"pre_process\":{\"kind\":\"kotlin\",\"script_ref\":\"/tmp/task6-evil.kts\"}}")
            .multiPart("name", "it_task6_" + System.nanoTime())
            .post("/api/platform/sources/csv/ingest")
            .then()
            .statusCode(400)
            .body("error", not(containsString("/tmp/task6-evil.kts")));
    }

    /**
     * #2001: a local ingest is the uploaded file. A local transport naming a server path — with or without its kind —
     * reads nothing and creates nothing, and the refusal does not echo the path.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "{\"kind\":\"local\",\"path\":\"/etc/hostname\"}",
        "{\"path\":\"/etc/hostname\"}",
        "{\"kind\":\"local\",\"path\":\"/etc/*\",\"max_file_size\":10}"})
    void a_local_transport_naming_a_server_path_is_refused(String transport) {
        String name = "it_2001_" + System.nanoTime();
        RestAssured.given()
            .header("X-Tenant-Id", "1")
            .multiPart("transport", transport)
            .multiPart("name", name)
            .post("/api/platform/sources/csv/ingest")
            .then()
            .statusCode(400)
            .body("error", containsString("a local ingest is the uploaded file"))
            .body("error", not(containsString("/etc")));
        assertEquals(0L, connectorsNamed(name), "nothing was created");
    }

    /** #2001: `after_process` on a local transport deleted or moved a file of the hub's disk; the file stays where it is. */
    @Test
    void a_local_after_process_leaves_the_servers_file_alone() throws Exception {
        Path victim = Files.createTempFile("it_2001_victim_", ".csv");
        Files.writeString(victim, "id,email\n1,a@x\n");
        Path archive = Files.createTempDirectory("it_2001_archive_");
        for (String action : new String[] {
                "\"after_process\":\"delete\"",
                "\"after_process\":\"move\",\"archive_path\":\"" + archive + "\""}) {
            RestAssured.given()
                .header("X-Tenant-Id", "1")
                .multiPart("transport", "{\"kind\":\"local\",\"path\":\"" + victim + "\"," + action + "}")
                .multiPart("name", "it_2001_" + System.nanoTime())
                .post("/api/platform/sources/csv/ingest")
                .then()
                .statusCode(400);
            assertEquals("id,email\n1,a@x\n", Files.readString(victim));
        }
        try (var archived = Files.list(archive)) {
            assertEquals(0L, archived.count());
        }
    }

    private long connectorsNamed(String name) {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM connector.connector WHERE tenant_id = 1 AND name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
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
