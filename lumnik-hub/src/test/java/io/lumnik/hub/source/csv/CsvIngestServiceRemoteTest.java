package io.lumnik.hub.source.csv;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.api.sources.CsvIngestForm;
import io.lumnik.hub.api.sources.CsvIngestResult;
import io.lumnik.hub.testsupport.MinioTestImage;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test: verifies that {@code CsvIngestService} can ingest a CSV file
 * from an S3-compatible store (MinIO) via the remote (FileSource) path.
 *
 * <p>The test uses {@link System#setProperty} to expose credentials so that
 * {@code S3FileSourceFactory}'s {@code @Inject} constructor (which falls back to
 * {@code System.getProperty} before {@code System.getenv}) can resolve them
 * without requiring real environment variables.
 */
@QuarkusTest
class CsvIngestServiceRemoteTest {

    static GenericContainer<?> minio;
    static String endpoint;
    static final String ACCESS = "csv-svc-test";
    static final String SECRET = "csv-svc-test-secret";
    static final String BUCKET = "csv-ingest-svc-test";

    @Inject
    CsvIngestService service;

    @BeforeAll
    static void start() {
        minio = new GenericContainer<>(MinioTestImage.PINNED)
            .withEnv("MINIO_ROOT_USER", ACCESS)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET)
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            // Wait for MinIO to SERVE, not merely to listen: the default strategy returns as
            // soon as the port accepts, and MinIO answers 503 for a window after that.
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000).forStatusCode(200));
        minio.start();
        // getHost() — Testcontainers may run on a remote Docker host, not localhost.
        endpoint = "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);

        try (S3Client admin = S3Client.builder()
                .region(Region.US_EAST_1)
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(ACCESS, SECRET)))
                .httpClient(UrlConnectionHttpClient.builder().build())
                .forcePathStyle(true)
                .build()) {
            admin.createBucket(b -> b.bucket(BUCKET));
            admin.putObject(
                b -> b.bucket(BUCKET).key("data/customers.csv"),
                RequestBody.fromString("id,email\n1,alice@x\n2,bob@x\n", StandardCharsets.UTF_8));
        }

        // Expose credentials via system properties so S3FileSourceFactory's @Inject ctor resolves them.
        System.setProperty("__INLINE_S3_ACCESS", ACCESS);
        System.setProperty("__INLINE_S3_SECRET", SECRET);
    }

    @AfterAll
    static void stop() {
        System.clearProperty("__INLINE_S3_ACCESS");
        System.clearProperty("__INLINE_S3_SECRET");
        if (minio != null) minio.stop();
    }

    @Test
    void ingests_csv_from_s3_via_service() throws Exception {
        CsvIngestForm form = new CsvIngestForm();
        form.name       = "svc_s3_test_" + System.nanoTime();
        form.scope      = "system";
        form.transport  = new HashMap<>(Map.of(
            "kind",           "s3",
            "region",         "us-east-1",
            "endpoint",       endpoint,
            "bucket",         BUCKET,
            "prefix",         "data/",
            "suffix",         ".csv",
            "access_key_env", "__INLINE_S3_ACCESS",
            "secret_key_env", "__INLINE_S3_SECRET"
        ));

        CsvIngestResult result = TenantContext.callAsSystem(() -> service.execute(form, 1L));

        assertEquals(2, result.rowsRead(),    "should have read 2 data rows");
        assertEquals(2, result.rowsWritten(), "should have written 2 rows");
        assertTrue(result.tableName().startsWith("ext."), "table must be in ext schema");
        assertNotNull(result.connectorId());
        assertNotNull(result.endpointId());
    }
}
