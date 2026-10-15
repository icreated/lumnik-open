package io.lumnik.hub.export.run;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.auth.NoBypassOidcProfile;
import io.lumnik.hub.export.ExportManifest;
import io.lumnik.hub.export.ExportManifestParser;
import io.lumnik.hub.testsupport.EntitySourceTableFixture;
import io.lumnik.hub.testsupport.MinioTestImage;
import io.lumnik.hub.testsupport.YamlBody;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * #848: the whole delivery path of a joined entity — {@code joins[]} → the Entity's
 * {@code security_invoker} view → snapshot CSV → S3 → run receipt — through the real HTTP doors
 * under real bearer JWTs, RLS enforced, the credentials in each tenant's platform secret registry.
 * The data is the demo's own CSV files, the Entity is the demo's own manifest and the export's
 * entity, scope, columns and order are the demo's own, so those cannot drift from the page (the two
 * connector manifests are not exercised: the tables are seeded directly). The per-door refusals are covered elsewhere
 * ({@code ExportRunResourceIT}, {@code EntityJoinScopeIT}); what this adds is that they still hold
 * on the assembled joined path, and that nothing of another tenant's lookup reaches the object.
 */
@QuarkusTest
@TestProfile(RlsOidcProfile.class)
class ExportEntityJoinS3IT {

    static final long T1 = 1L;
    static final long T2 = 2L;
    static final String BUCKET = "export-join-bucket";
    static final String PREFIX = "catalogue";
    static final String ACCESS = "minio-join";
    static final String SECRET = "minio-join-848-secret";
    static final String ACCESS_NAME = "EXPORT_JOIN_848_S3_ACCESS";
    static final String SECRET_NAME = "EXPORT_JOIN_848_S3_SECRET";
    /** Another tenant's category text: it must never be found in anything tenant 1 receives. */
    static final String SENTINEL = "SENTINEL-OTHER-TENANT-848";

    static final Path DOCS = Path.of("..", "docs");

    static GenericContainer<?> minio;
    static String endpoint;
    static S3Client s3;

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @BeforeAll
    static void startMinio() {
        minio = new GenericContainer<>(MinioTestImage.PINNED)
            .withEnv("MINIO_ROOT_USER", ACCESS)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET)
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000).forStatusCode(200));
        minio.start();
        endpoint = "http://localhost:" + minio.getMappedPort(9000);
        s3 = S3Client.builder()
            .region(Region.US_EAST_1)
            .endpointOverride(URI.create(endpoint))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS, SECRET)))
            .httpClient(UrlConnectionHttpClient.builder().build())
            .forcePathStyle(true)
            .build();
        s3.createBucket(b -> b.bucket(BUCKET));
    }

    @AfterAll
    static void stopMinio() {
        if (s3 != null) s3.close();
        if (minio != null) minio.stop();
    }

    // ---------------------------------------------------------------- fixtures

    static String bearer(long tenant, String... roles) {
        return "Bearer " + NoBypassOidcProfile.token(Set.of(roles), null, tenant);
    }

    /** The tenant-1 caller of the whole path: builds and delivers the catalogue. */
    static String integrator() {
        return bearer(T1, "lm_integrator", "scope:catalogue");
    }

    static String adminCatalogue() {
        return bearer(T1, "lm_admin", "scope:catalogue");
    }

    @BeforeEach
    void seed() throws Exception {
        EntitySourceTableFixture.create(adminDs, "t_products", "v_productwithcategory",
            "m_product_id", "m_product_category_id", "name");
        EntitySourceTableFixture.create(adminDs, "t_product_categories", "v_productwithcategory",
            "m_product_category_id", "name", "description");
        insertCsv(T1, "t_products", "apps/demo/data/m_product.csv");
        insertCsv(T1, "t_product_categories", "apps/demo/data/m_product_category.csv");
        // Tenant 2's lookup: C9 is the key tenant 1's P3 points at and tenant 1 never declared;
        // C1 would make tenant 1's real C1 ambiguous if the lookup crossed tenants.
        Jdbc.update(adminDs, "tenant 2 lookup",
            "INSERT INTO connector.t_product_categories (tenant_id, m_product_category_id, name, description) "
          + "VALUES (2, 'C9', '" + SENTINEL + "', '" + SENTINEL + " d'), "
          + "(2, 'C1', '" + SENTINEL + "', '" + SENTINEL + " d')", ps -> {});
        registerSecrets(T1);
        given().config(YamlBody.CONFIG).header("Authorization", adminCatalogue())
            .contentType("application/yaml").body(read("apps/demo/entities/30-product-with-category.yaml"))
            .post("/api/entities/apply").then().statusCode(200);
    }

    /** The demo's CSV, row by row, as tenant {@code tenant}'s rows of {@code table} (empty cell = NULL). */
    private void insertCsv(long tenant, String table, String docsFile) throws IOException {
        try (CSVParser csv = CSVParser.parse(read(docsFile),
                CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build())) {
            List<String> columns = csv.getHeaderNames();
            for (CSVRecord rec : csv.getRecords()) {
                Jdbc.update(adminDs, "seed " + table,
                    "INSERT INTO connector." + table + " (tenant_id, " + String.join(", ", columns) + ") VALUES (?"
                  + ", ?".repeat(columns.size()) + ")",
                    ps -> {
                        ps.setLong(1, tenant);
                        for (int i = 0; i < columns.size(); i++)
                            ps.setString(i + 2, rec.get(i).isEmpty() ? null : rec.get(i));
                    });
            }
        }
    }

    static String read(String docsFile) throws IOException {
        return Files.readString(DOCS.resolve(docsFile));
    }

    static void registerSecrets(long tenant) {
        for (String[] s : new String[][] {{ACCESS_NAME, ACCESS}, {SECRET_NAME, SECRET}}) {
            int status = given().header("Authorization", bearer(tenant, "lm_integrator", "lm_admin"))
                .contentType("application/json")
                .body("{\"name\":\"" + s[0] + "\",\"value\":\"" + s[1] + "\"}")
                .post("/api/platform/secrets").statusCode();
            assertThat(status).as("register %s for tenant %d", s[0], tenant).isIn(201, 409);
        }
    }

    /**
     * The shipped export manifest (entity, scope, columns, order) pointed at the test MinIO: the
     * docs file decides WHAT is exported, the test only decides WHERE.
     */
    ExportManifest shipped() throws IOException {
        return new ExportManifestParser().parse(read("apps/exports/30-product-catalogue-s3.yaml"));
    }

    String applyExport(String name, String target) throws IOException {
        ExportManifest shipped = shipped();
        String yaml = """
            apiVersion: apps.lumnik.io/v1
            kind: Export
            metadata:
              name: %s
            spec:
              scope: %s
              source:
                entity: %s
              columns: %s
              order: %s
              format:
                kind: csv
              destination:
                kind: s3
                bucket: %s
                prefix: %s
                region: us-east-1
                endpoint: %s
                filename: %s
                access_key_env: %s
                secret_key_env: %s
              mode: snapshot
            """.formatted(name, shipped.scope(), shipped.entity(), shipped.columns(), shipped.order(),
                BUCKET, PREFIX, endpoint, target, ACCESS_NAME, SECRET_NAME);
        given().config(YamlBody.CONFIG).header("Authorization", integrator())
            .contentType("application/yaml").body(yaml)
            .post("/api/exports/apply").then().statusCode(200);
        return name;
    }

    static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    static String start(String name) {
        return given().header("Authorization", integrator()).post("/api/exports/" + name + "/runs")
            .then().statusCode(202).body("runId", notNullValue()).extract().path("runId");
    }

    /** Polls the run until it leaves Running (bounded: 60 s); returns the final body. */
    static Response awaitTerminal(String name, String runId) throws InterruptedException {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (true) {
            Response r = given().header("Authorization", integrator())
                .get("/api/exports/" + name + "/runs/" + runId);
            r.then().statusCode(200);
            if (!"Running".equals(r.path("status"))) return r;
            assertThat(System.nanoTime()).as("run %s still Running after 60 s", runId).isLessThan(deadline);
            Thread.sleep(100);
        }
    }

    static byte[] remote(String target) {
        try {
            return s3.getObjectAsBytes(b -> b.bucket(BUCKET).key(PREFIX + "/" + target)).asByteArray();
        } catch (NoSuchKeyException e) {
            return null;
        }
    }

    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    // ---------------------------------------------------------------- the path

    @Test
    void aJoinedEntityIsDeliveredToS3WithItsCategoriesAndNothingOfAnotherTenant() throws Exception {
        String target = unique("join") + ".csv";
        String name = applyExport(unique("join-export"), target);

        Response run = awaitTerminal(name, start(name));

        assertThat((String) run.path("status"))
            .as("run status (failureCode %s: %s)", run.path("failureCode"), run.path("failureReason"))
            .isEqualTo("Completed");
        byte[] bytes = remote(target);
        assertThat(bytes).as("the object reached the bucket").isNotNull();
        String csv = new String(bytes, StandardCharsets.UTF_8);
        // Exactly four data rows, one per product: P1 and P2 share category C1 and are NOT merged.
        // P3's key (C9) exists only in tenant 2 -> empty; P4's key (C2) is listed twice -> empty,
        // never the first or the last of the duplicates.
        assertThat(csv).isEqualTo(
            "\"product_id\",\"product_name\",\"category_id\",\"category_name\",\"category_description\"\r\n"
          + "\"P1\",\"Pump 50\",\"C1\",\"Pumps\",\"Centrifugal and gear pumps\"\r\n"
          + "\"P2\",\"Pump 80\",\"C1\",\"Pumps\",\"Centrifugal and gear pumps\"\r\n"
          + "\"P3\",\"Spare gasket\",\"C9\",,\r\n"
          + "\"P4\",\"Valve DN15\",\"C2\",,\r\n");
        assertThat(csv).doesNotContain(SENTINEL);

        // The receipt describes the object that is really there.
        assertThat((Integer) run.path("rowCount")).isEqualTo(4);
        assertThat((Integer) run.path("byteCount")).isEqualTo(bytes.length);
        assertThat((String) run.path("sha256")).isEqualTo(sha256(bytes));
        assertThat((String) run.path("location")).isEqualTo("s3://" + BUCKET + "/" + PREFIX + "/" + target);
        assertThat((String) run.path("etag")).isNotBlank();
    }

    // ---------------------------------------------------------------- the leak lanes

    @Test
    void aCallerOutsideTheCatalogueScopeCannotReadTheJoinedEntity() {
        given().header("Authorization", bearer(T1, "lm_user", "scope:magasin"))
            .get("/api/entities/productwithcategory")
            .then().statusCode(403).body("error", equalTo("scope not granted"));
    }

    @Test
    void aCallerOutsideTheCatalogueScopeCannotStartTheExportAndNothingIsDelivered() throws Exception {
        String target = unique("outofscope") + ".csv";
        String name = applyExport(unique("scoped-export"), target);

        given().header("Authorization", bearer(T1, "lm_integrator", "scope:magasin"))
            .post("/api/exports/" + name + "/runs")
            .then().statusCode(403).body("error", equalTo("scope not granted"));

        assertThat(remote(target)).as("no object for a refused start").isNull();
        assertThat(runRows(name)).isZero();
    }

    @Test
    void aCallerWhoIsNotAnIntegratorIsRefusedOnTheExportDoors() throws Exception {
        String name = applyExport(unique("gated-export"), unique("gated") + ".csv");
        String user = bearer(T1, "lm_user", "scope:catalogue");

        given().header("Authorization", user).post("/api/exports/" + name + "/runs")
            .then().statusCode(403);
        given().header("Authorization", user).get("/api/exports/" + name)
            .then().statusCode(403);
        given().header("Authorization", user).get("/api/exports/" + name + "/runs")
            .then().statusCode(403);
    }

    @Test
    void anotherTenantCannotStartOrSeeTheExport() throws Exception {
        String target = unique("tenancy") + ".csv";
        String name = applyExport(unique("tenant-export"), target);
        String runId = start(name);
        awaitTerminal(name, runId);
        String other = bearer(T2, "lm_integrator", "scope:catalogue");

        given().header("Authorization", other).post("/api/exports/" + name + "/runs")
            .then().statusCode(404);
        given().header("Authorization", other).get("/api/exports/" + name + "/runs/" + runId)
            .then().statusCode(404);
        given().header("Authorization", other).get("/api/exports/" + name)
            .then().statusCode(404);
        assertThat(Jdbc.queryOne(adminDs, "tenant 2 runs " + name,
            "SELECT count(*) FROM connector.export_run WHERE export_name = ? AND tenant_id = 2",
            ps -> ps.setString(1, name), rs -> rs.getLong(1)).orElseThrow()).isZero();
    }

    private long runRows(String name) {
        return Jdbc.queryOne(adminDs, "runs " + name,
            "SELECT count(*) FROM connector.export_run WHERE export_name = ?",
            ps -> ps.setString(1, name), rs -> rs.getLong(1)).orElseThrow();
    }
}
