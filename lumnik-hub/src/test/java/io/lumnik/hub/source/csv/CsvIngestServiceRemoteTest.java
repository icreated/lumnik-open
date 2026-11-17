package io.lumnik.hub.source.csv;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.api.sources.CsvIngestForm;
import io.lumnik.hub.api.sources.CsvIngestResult;
import io.lumnik.hub.outbound.OutboundRefusedException;
import io.lumnik.hub.secret.SecretNotServedException;
import io.lumnik.hub.testsupport.MinioTestImage;
import io.lumnik.hub.testsupport.TestSecrets;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test: verifies that {@code CsvIngestService} can ingest a CSV file
 * from an S3-compatible store (MinIO) via the remote (FileSource) path.
 *
 * <p>The credentials are tenant 1's lm secrets, where {@code S3FileSourceFactory} resolves them.
 */
@QuarkusTest
class CsvIngestServiceRemoteTest {

    @Inject TestSecrets testSecrets;

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
    }

    @AfterAll
    static void stop() {
        if (minio != null) minio.stop();
    }

    /**
     * The connector's secrets, in tenant 1's lm secret registry: the only place a `*_env` reference resolves (#2000) —
     * serving the ingest's scope, which every one-shot connector carries (#2015).
     */
    @BeforeEach
    void holdTheSecrets() {
        testSecrets.put(1L, "__INLINE_S3_ACCESS", ACCESS, List.of("system"));
        testSecrets.put(1L, "__INLINE_S3_SECRET", SECRET, List.of("system"));
    }

    @Test
    void ingests_csv_from_s3_via_service() throws Exception {
        CsvIngestResult result = TenantContext.callAsSystem(() -> service.execute(s3Form(), 1L));

        assertEquals(2, result.rowsRead(),    "should have read 2 data rows");
        assertEquals(2, result.rowsWritten(), "should have written 2 rows");
        assertTrue(result.tableName().startsWith("ext."), "table must be in ext schema");
        assertNotNull(result.connectorId());
        assertNotNull(result.endpointId());
    }

    /**
     * #2015: the ingest's connector is in scope system; a secret that serves no scope is refused, by name — and as
     * itself, a configuration problem the door answers {@code 400}, not wrapped into a transport failure's {@code 500}.
     */
    @Test
    void a_secret_outside_the_ingests_scope_is_refused() {
        testSecrets.put(1L, "__INLINE_S3_SECRET", SECRET);

        var refused = assertThrows(SecretNotServedException.class,
            () -> TenantContext.callAsSystem(() -> service.execute(s3Form(), 1L)));

        assertTrue(refused.getMessage().contains("secret __INLINE_S3_SECRET does not serve scope system"),
            refused.getMessage());
    }

    /** #2002: an endpoint the hub refuses is refused as itself — the door's 400 — not wrapped into a transport's 500. */
    @Test
    void an_endpoint_the_hub_refuses_is_refused_as_itself() {
        CsvIngestForm form = s3Form();
        form.transport.put("endpoint", "http://169.254.169.254:9000");

        var refused = assertThrows(OutboundRefusedException.class,
            () -> TenantContext.callAsSystem(() -> service.execute(form, 1L)));

        assertTrue(refused.getMessage().contains("never connects to"), refused.getMessage());
    }

    private CsvIngestForm s3Form() {
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
        return form;
    }
}
