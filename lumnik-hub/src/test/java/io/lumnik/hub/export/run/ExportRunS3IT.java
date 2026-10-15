package io.lumnik.hub.export.run;

import io.lumnik.hub.testsupport.MinioTestImage;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link ExportRunITSupport}'s scenarios over the #679 S3 destination (same pinned MinIO). */
@QuarkusTest
class ExportRunS3IT extends ExportRunScenarios {

    static GenericContainer<?> minio;
    static String endpoint;
    static S3Client admin;
    static final String ACCESS = "minio-test";
    static final String SECRET = "minio-test-secret";
    static final String BUCKET = "export-run-bucket";
    static final String ACCESS_ENV = SECRET_ENV_PREFIX + "S3_ACCESS";
    static final String SECRET_ENV = SECRET_ENV_PREFIX + "S3_SECRET";
    static final String WRONG_ENV = SECRET_ENV_PREFIX + "S3_WRONG";

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
        admin = S3Client.builder()
            .region(Region.US_EAST_1)
            .endpointOverride(URI.create(endpoint))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS, SECRET)))
            .httpClient(UrlConnectionHttpClient.builder().build())
            .forcePathStyle(true)
            .build();
        admin.createBucket(b -> b.bucket(BUCKET));
        // SecretRefs falls back to a JVM system property when the tenant registry has no entry.
        System.setProperty(ACCESS_ENV, ACCESS);
        System.setProperty(SECRET_ENV, SECRET);
        System.setProperty(WRONG_ENV, WRONG_SECRET);
    }

    @AfterAll
    static void stopMinio() {
        System.clearProperty(ACCESS_ENV);
        System.clearProperty(SECRET_ENV);
        System.clearProperty(WRONG_ENV);
        if (admin != null) admin.close();
        if (minio != null) minio.stop();
    }

    @Override
    String entity() {
        return "ExportRunS3Entity680";
    }

    @Override
    String destination(String target, boolean wrongCredential) {
        return """
                kind: s3
                bucket: %s
                prefix: runs
                region: us-east-1
                endpoint: %s
                filename: %s
                access_key_env: %s
                secret_key_env: %s
            """.formatted(BUCKET, endpoint, target, ACCESS_ENV,
                wrongCredential ? WRONG_ENV : SECRET_ENV)
            .stripTrailing();
    }

    @Override
    byte[] remote(String target) {
        try {
            return admin.getObjectAsBytes(b -> b.bucket(BUCKET).key("runs/" + target)).asByteArray();
        } catch (NoSuchKeyException e) {
            return null;
        }
    }

    @Override
    String location(String target) {
        return "s3://" + BUCKET + "/runs/" + target;
    }

    @Override
    void assertNoLeftover(String target) {
        assertThat(admin.listMultipartUploads(b -> b.bucket(BUCKET).prefix("runs/" + target)).uploads())
            .isEmpty();
    }

    @Override
    void assertReceipt(ExportRunView run) {
        assertThat(run.etag()).isNotBlank();
        assertThat(run.versionId()).isNull();
    }
}
