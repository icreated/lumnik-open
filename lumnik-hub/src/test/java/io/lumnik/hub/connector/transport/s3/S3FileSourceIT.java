package io.lumnik.hub.connector.transport.s3;

import io.lumnik.hub.connector.transport.AfterProcessAction;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class S3FileSourceIT {

    static GenericContainer<?> minio;
    static String endpoint;
    static final String ACCESS = "minio-test";
    static final String SECRET = "minio-test-secret";
    static final String BUCKET = "test-bucket";

    @BeforeAll
    static void start() {
        minio = new GenericContainer<>("minio/minio:RELEASE.2025-09-07T16-13-09Z")
            .withEnv("MINIO_ROOT_USER", ACCESS)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET)
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            // Wait for MinIO to SERVE, not merely to listen: the default strategy returns as
            // soon as the port accepts, and MinIO answers 503 for a window after that.
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000).forStatusCode(200));
        minio.start();
        endpoint = "http://localhost:" + minio.getMappedPort(9000);

        try (S3Client admin = S3Client.builder()
                .region(Region.US_EAST_1)
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(ACCESS, SECRET)))
                .httpClient(UrlConnectionHttpClient.builder().build())
                .forcePathStyle(true)
                .build()) {
            admin.createBucket(b -> b.bucket(BUCKET));
            admin.putObject(b -> b.bucket(BUCKET).key("billing/a.csv"),
                RequestBody.fromString("a,1\n", StandardCharsets.UTF_8));
            admin.putObject(b -> b.bucket(BUCKET).key("billing/b.csv"),
                RequestBody.fromString("b,2\n", StandardCharsets.UTF_8));
            admin.putObject(b -> b.bucket(BUCKET).key("other/c.csv"),
                RequestBody.fromString("c,3\n", StandardCharsets.UTF_8));
            admin.putObject(b -> b.bucket(BUCKET).key("seek/seek.csv"),
                RequestBody.fromString("0123456789ABCDEFGHIJ", StandardCharsets.UTF_8));
        }
    }

    @AfterAll
    static void stop() {
        if (minio != null) minio.stop();
    }

    @Test
    void list_filters_by_prefix_and_suffix_and_cursor() throws Exception {
        Function<String, String> env = name -> switch (name) {
            case "AWS_S3_ACCESS_KEY" -> ACCESS;
            case "AWS_S3_SECRET_KEY" -> SECRET;
            default -> null;
        };
        var factory = new S3FileSourceFactory(env);
        var source = factory.build(Map.of(
            "region", "us-east-1",
            "endpoint", endpoint,
            "bucket", BUCKET,
            "prefix", "billing/",
            "suffix", ".csv",
            "access_key_env", "AWS_S3_ACCESS_KEY",
            "secret_key_env", "AWS_S3_SECRET_KEY"
        ));

        var listed = source.listSince(Instant.EPOCH).toList();
        assertEquals(2, listed.size(), "prefix should isolate billing/ scope");

        try (InputStream in = source.open(listed.get(0))) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(content.startsWith("a,") || content.startsWith("b,"));
        }

        source.markProcessed(listed.get(0), AfterProcessAction.DELETE, Map.of());
        var afterDelete = source.listSince(Instant.EPOCH).toList();
        assertEquals(1, afterDelete.size(), "DELETE should have removed one object");

        source.close();
    }

    @Test
    void openAt_uses_a_ranged_getObject_to_start_exactly_at_the_requested_byte_offset() throws Exception {
        Function<String, String> env = name -> switch (name) {
            case "AWS_S3_ACCESS_KEY" -> ACCESS;
            case "AWS_S3_SECRET_KEY" -> SECRET;
            default -> null;
        };
        var factory = new S3FileSourceFactory(env);
        var source = (S3FileSource) factory.build(Map.of(
            "region", "us-east-1",
            "endpoint", endpoint,
            "bucket", BUCKET,
            "prefix", "seek/",
            "suffix", ".csv",
            "access_key_env", "AWS_S3_ACCESS_KEY",
            "secret_key_env", "AWS_S3_SECRET_KEY"
        ));

        var desc = source.listSince(Instant.EPOCH).findFirst().orElseThrow();

        try (InputStream in = source.openAt(desc, 10)) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("ABCDEFGHIJ", content,
                "ranged GetObject must start exactly at byte 10, not the whole object skipped-and-discarded");
        }

        try (InputStream in = source.openAt(desc, 0)) {
            assertEquals("0123456789ABCDEFGHIJ", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }

        assertThrows(IllegalArgumentException.class, () -> source.openAt(desc, -1));

        // SeekableSource contract: an offset at/after EOF is an empty stream, not a 416 — matching
        // Local and SFTP. The object is 20 bytes; a resume anchor that lands on EOF must not throw.
        try (InputStream in = source.openAt(desc, desc.size())) {
            assertEquals(0, in.readAllBytes().length,
                "an offset at EOF must yield an empty stream, not a ranged-GET 416");
        }
        try (InputStream in = source.openAt(desc, desc.size() + 100)) {
            assertEquals(0, in.readAllBytes().length, "an offset past EOF must yield an empty stream too");
        }

        source.close();
    }
}
