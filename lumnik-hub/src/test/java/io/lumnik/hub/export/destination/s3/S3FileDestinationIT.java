package io.lumnik.hub.export.destination.s3;

import io.lumnik.hub.connector.secret.SecretRefs;
import io.lumnik.hub.export.destination.FileDestinationCollisionException;
import io.lumnik.hub.export.destination.FileDestinationException;
import io.lumnik.hub.export.destination.PublishedArtifact;
import io.lumnik.hub.testsupport.MinioTestImage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListPartsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class S3FileDestinationIT {

    static GenericContainer<?> minio;
    static String endpoint;
    static S3Client admin;
    static final String ACCESS = "minio-test";
    static final String SECRET = "minio-test-secret";
    static final String BUCKET = "export-bucket";
    static final String VERSIONED = "export-versioned";
    static final int PART = S3UploadStream.MIN_PART_SIZE;

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
        endpoint = "http://localhost:" + minio.getMappedPort(9000);

        admin = client(ACCESS, SECRET, null);
        admin.createBucket(b -> b.bucket(BUCKET));
        admin.createBucket(b -> b.bucket(VERSIONED));
        admin.putBucketVersioning(b -> b.bucket(VERSIONED)
            .versioningConfiguration(v -> v.status(BucketVersioningStatus.ENABLED)));
    }

    @AfterAll
    static void stop() {
        if (admin != null) admin.close();
        if (minio != null) minio.stop();
    }

    static S3Client client(String access, String secret, Duration attemptTimeout) {
        var builder = S3Client.builder()
            .region(Region.US_EAST_1)
            .endpointOverride(URI.create(endpoint))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret)))
            .httpClient(UrlConnectionHttpClient.builder().build())
            .forcePathStyle(true);
        if (attemptTimeout != null) {
            builder.overrideConfiguration(o -> o.apiCallAttemptTimeout(attemptTimeout).apiCallTimeout(attemptTimeout));
        }
        return builder.build();
    }

    static S3FileDestinationFactory factory() {
        return factory(ACCESS, SECRET);
    }

    static S3FileDestinationFactory factory(String access, String secret) {
        Function<String, String> env = name -> switch (name) {
            case "AWS_S3_ACCESS_KEY" -> access;
            case "AWS_S3_SECRET_KEY" -> secret;
            default -> null;
        };
        return new S3FileDestinationFactory(SecretRefs.environmentOnly(env), PART);
    }

    static Map<String, Object> cfg(String bucket, String prefix, String filename) {
        return Map.of(
            "region", "us-east-1",
            "endpoint", endpoint,
            "bucket", bucket,
            "prefix", prefix,
            "filename", filename,
            "access_key_env", "AWS_S3_ACCESS_KEY",
            "secret_key_env", "AWS_S3_SECRET_KEY");
    }

    static Map<String, Object> cfg(String filename) {
        return cfg(BUCKET, "", filename);
    }

    static byte[] randomBytes(int size, long seed) {
        byte[] bytes = new byte[size];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    static byte[] get(String bucket, String key) {
        return admin.getObjectAsBytes(b -> b.bucket(bucket).key(key)).asByteArray();
    }

    static boolean exists(String key) {
        try {
            admin.headObject(b -> b.bucket(BUCKET).key(key));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    /** The S3 counterpart of SFTP's "no leftover .part": no multipart upload is pending under key. */
    static void assertNoPendingUploads(String key) {
        assertThat(admin.listMultipartUploads(b -> b.bucket(BUCKET).prefix(key)).uploads()).isEmpty();
    }

    static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    void single_part_publishes_the_exact_bytes() throws Exception {
        byte[] content = "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8);
        try (var destination = (S3FileDestination) factory().build(cfg("single.csv"))) {
            destination.open().write(content);
            PublishedArtifact receipt = destination.commit();

            assertThat(receipt.kind()).isEqualTo("s3");
            assertThat(receipt.location()).isEqualTo("s3://" + BUCKET + "/single.csv");
            assertThat(receipt.etag()).isNotNull();
            assertThat(receipt.versionId()).isNull();
        }
        assertThat(get(BUCKET, "single.csv")).isEqualTo(content);
        HeadObjectResponse head = admin.headObject(b -> b.bucket(BUCKET).key("single.csv"));
        assertThat(head.metadata()).containsEntry("lumnik-sha256", sha256Hex(content));
    }

    @Test
    void multipart_sized_content_publishes_the_exact_bytes() throws Exception {
        byte[] content = randomBytes(PART * 2 + 1024 * 1024, 11); // ~11 MiB: 3 parts
        try (var destination = (S3FileDestination) factory().build(cfg("multi.bin"))) {
            OutputStream out = destination.open();
            out.write(content);

            // Two parts are already sent (the third waits in the buffer): the upload was created
            // with SHA-256 declared, and each part carries its own SHA-256.
            String uploadId = destination.uploadId();
            assertThat(uploadId).isNotNull();
            ListPartsResponse listed = admin.listParts(b -> b.bucket(BUCKET).key("multi.bin").uploadId(uploadId));
            assertThat(listed.checksumAlgorithm()).isEqualTo(ChecksumAlgorithm.SHA256);
            assertThat(listed.parts()).hasSize(2).allSatisfy(p -> assertThat(p.checksumSHA256()).isNotBlank());

            PublishedArtifact receipt = destination.commit();

            assertThat(receipt.etag()).endsWith("-3\"");
            HeadObjectResponse head = admin.headObject(b -> b.bucket(BUCKET).key("multi.bin"));
            assertThat(receipt.etag()).isEqualTo(head.eTag());
            assertThat(head.metadata()).doesNotContainKey("lumnik-sha256");
        }
        assertThat(get(BUCKET, "multi.bin")).isEqualTo(content);
        assertNoPendingUploads("multi.bin");
    }

    @Test
    void exactly_one_part_stays_single_part() throws Exception {
        byte[] content = randomBytes(PART, 1);
        try (var destination = (S3FileDestination) factory().build(cfg("exact.bin"))) {
            destination.open().write(content);
            assertThat(destination.uploadId()).as("no multipart upload was ever created").isNull();
            assertNoPendingUploads("exact.bin");
            PublishedArtifact receipt = destination.commit();
            assertThat(receipt.etag()).doesNotContain("-");
        }
        assertThat(get(BUCKET, "exact.bin")).isEqualTo(content);
    }

    @Test
    void empty_stream_publishes_an_empty_object() throws Exception {
        try (var destination = (S3FileDestination) factory().build(cfg("empty.csv"))) {
            destination.open();
            destination.commit();
        }
        assertThat(get(BUCKET, "empty.csv")).isEmpty();
    }

    @Test
    void interrupted_upload_never_exposes_the_final_key() throws Exception {
        try (var destination = (S3FileDestination) factory().build(cfg("interrupted.bin"))) {
            destination.open().write(randomBytes(PART * 2 + 1, 2));
            assertThat(destination.uploadId()).isNotNull();

            destination.abort();

            assertThat(exists("interrupted.bin")).isFalse();
            assertNoPendingUploads("interrupted.bin");
        }
    }

    @Test
    void close_without_commit_aborts() throws Exception {
        try (var destination = (S3FileDestination) factory().build(cfg("close-no-commit.bin"))) {
            destination.open().write(randomBytes(PART * 2 + 1, 3));
        }
        assertThat(exists("close-no-commit.bin")).isFalse();
        assertNoPendingUploads("close-no-commit.bin");
    }

    /**
     * MinIO is paused mid-stream (no {@code mc} needed): the next part upload times out, so
     * {@code write} throws. Once the store is back, {@code abort()} discards the parts it owns.
     */
    @Test
    void a_failed_part_upload_aborts_and_publishes_nothing() throws Exception {
        String key = "failed-part.bin";
        var docker = DockerClientFactory.instance().client();
        var destination = new S3FileDestination(client(ACCESS, SECRET, Duration.ofSeconds(3)), BUCKET, key, PART);
        try (destination) {
            OutputStream out = destination.open();
            out.write(randomBytes(PART + 1, 4)); // part 1 sent, 1 byte waiting

            docker.pauseContainerCmd(minio.getContainerId()).exec();
            Throwable thrown;
            try {
                thrown = catchThrowable(() -> out.write(randomBytes(PART, 5)));
            } finally {
                docker.unpauseContainerCmd(minio.getContainerId()).exec();
            }
            assertThat(thrown).isInstanceOf(FileDestinationException.class).hasMessageContaining(key);

            destination.abort();
        }
        assertThat(exists(key)).isFalse();
        assertNoPendingUploads(key);
    }

    @Test
    void a_collision_refuses_without_touching_the_existing_object() throws Exception {
        admin.putObject(b -> b.bucket(BUCKET).key("collide.csv"), RequestBody.fromString("original\n"));
        String etagBefore = admin.headObject(b -> b.bucket(BUCKET).key("collide.csv")).eTag();

        assertThatThrownBy(() -> factory().build(cfg("collide.csv")))
            .isInstanceOf(FileDestinationCollisionException.class);

        assertThat(new String(get(BUCKET, "collide.csv"), StandardCharsets.UTF_8)).isEqualTo("original\n");
        assertThat(admin.headObject(b -> b.bucket(BUCKET).key("collide.csv")).eTag()).isEqualTo(etagBefore);
    }

    @ParameterizedTest
    @ValueSource(ints = {10, PART * 2 + 10})
    void a_collision_racing_past_the_precheck_is_refused_at_commit(int size) throws Exception {
        String key = "race-past-" + size + ".bin";
        try (var destination = (S3FileDestination) factory().build(cfg(key))) {
            destination.open().write(randomBytes(size, 6));
            admin.putObject(b -> b.bucket(BUCKET).key(key), RequestBody.fromString("seeded\n"));
            String etagBefore = admin.headObject(b -> b.bucket(BUCKET).key(key)).eTag();

            assertThatThrownBy(destination::commit).isInstanceOf(FileDestinationCollisionException.class);

            assertThat(new String(get(BUCKET, key), StandardCharsets.UTF_8)).isEqualTo("seeded\n");
            assertThat(admin.headObject(b -> b.bucket(BUCKET).key(key)).eTag()).isEqualTo(etagBefore);
            assertNoPendingUploads(key);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {10, PART * 2 + 10})
    void two_concurrent_runs_on_one_key_publish_exactly_one(int size) throws Exception {
        String key = "race-" + size + ".bin";
        byte[] p1 = randomBytes(size, 7);
        byte[] p2 = randomBytes(size, 8);
        try (var d1 = (S3FileDestination) factory().build(cfg(key));
             var d2 = (S3FileDestination) factory().build(cfg(key))) {
            d1.open().write(p1);
            d2.open().write(p2);

            var barrier = new CyclicBarrier(2);
            var results = new ConcurrentHashMap<String, Object>();
            Thread t1 = new Thread(() -> {
                try { barrier.await(); results.put("d1", d1.commit()); }
                catch (Throwable t) { results.put("d1", t); }
            });
            Thread t2 = new Thread(() -> {
                try { barrier.await(); results.put("d2", d2.commit()); }
                catch (Throwable t) { results.put("d2", t); }
            });
            t1.start(); t2.start();
            t1.join(); t2.join();

            boolean d1Won = results.get("d1") instanceof PublishedArtifact;
            boolean d2Won = results.get("d2") instanceof PublishedArtifact;
            assertThat(d1Won ^ d2Won).as("exactly one of the two racing commits publishes: " + results).isTrue();
            // The loser sees 412 (the winner already landed: a collision) or 409 ConditionalRequestConflict
            // (the winner is still in flight: an ordinary failure, nothing delivered yet). Both are legal.
            Object loser = d1Won ? results.get("d2") : results.get("d1");
            assertThat(loser).isInstanceOf(FileDestinationException.class);
            if (!(loser instanceof FileDestinationCollisionException))
                assertThat(((FileDestinationException) loser).getCause())
                    .isInstanceOfSatisfying(AwsServiceException.class, e -> assertThat(e.statusCode()).isEqualTo(409));
            assertThat(get(BUCKET, key)).isEqualTo(d1Won ? p1 : p2);
            assertNoPendingUploads(key);
        }
    }

    @Test
    void a_versioned_bucket_reports_the_version_id() throws Exception {
        PublishedArtifact receipt;
        try (var destination = (S3FileDestination) factory().build(cfg(VERSIONED, "", "versioned.csv"))) {
            destination.open().write("v".getBytes(StandardCharsets.UTF_8));
            receipt = destination.commit();
        }
        String headVersion = admin.headObject(b -> b.bucket(VERSIONED).key("versioned.csv")).versionId();
        assertThat(receipt.versionId()).isNotNull().isEqualTo(headVersion);
    }

    @Test
    void prefix_is_joined_as_a_folder() throws Exception {
        try (var destination = (S3FileDestination) factory().build(cfg(BUCKET, "customers", "x.csv"))) {
            destination.open().write('x');
            assertThat(destination.commit().location()).isEqualTo("s3://" + BUCKET + "/customers/x.csv");
        }
        // The same key through "customers/" — already delivered, so the pre-check refuses it.
        assertThatThrownBy(() -> factory().build(cfg(BUCKET, "customers/", "x.csv")))
            .isInstanceOf(FileDestinationCollisionException.class);
        assertThatThrownBy(() -> factory().build(cfg(BUCKET, "/customers", "y.csv")))
            .isInstanceOf(FileDestinationException.class)
            .isNotInstanceOf(FileDestinationCollisionException.class);
    }

    @Test
    void lifecycle_transitions_fail_stably() throws Exception {
        try (var destination = (S3FileDestination) factory().build(cfg("lifecycle-1.csv"))) {
            assertThatThrownBy(destination::commit).as("commit before open").isInstanceOf(IllegalStateException.class);
            OutputStream out = destination.open();
            assertThatThrownBy(destination::open).as("open twice").isInstanceOf(IllegalStateException.class);
            out.write('x'); // the first stream is untouched
            destination.commit();
            assertThatThrownBy(destination::abort).as("abort after commit").isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(destination::commit).as("commit twice").isInstanceOf(IllegalStateException.class);
        }
        assertThat(exists("lifecycle-1.csv")).isTrue();
        try (var destination = (S3FileDestination) factory().build(cfg("lifecycle-2.csv"))) {
            destination.open();
            destination.abort();
            destination.abort(); // idempotent
            assertThatThrownBy(destination::commit).as("commit after abort").isInstanceOf(IllegalStateException.class);
        }
        assertThat(exists("lifecycle-2.csv")).isFalse();
    }

    /**
     * Needs {@code mc} inside the MinIO image to create a PutObject-only user. The pinned
     * upstream image ships it.
     */
    @Test
    void write_only_bucket_passes_the_precheck() throws Exception {
        String policy = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":["s3:PutObject"],
             "Resource":["arn:aws:s3:::%s/*"]}]}""".formatted(BUCKET);
        mc("alias", "set", "local", "http://localhost:9000", ACCESS, SECRET);
        mc("admin", "user", "add", "local", "writer", "writer-secret");
        minio.copyFileToContainer(
            Transferable.of(policy), "/tmp/put-only.json");
        mc("admin", "policy", "create", "local", "put-only", "/tmp/put-only.json");
        mc("admin", "policy", "attach", "local", "put-only", "--user", "writer");

        try (var destination = (S3FileDestination) factory("writer", "writer-secret").build(cfg("write-only.csv"))) {
            destination.open().write("w".getBytes(StandardCharsets.UTF_8));
            destination.commit();
        }
        assertThat(get(BUCKET, "write-only.csv")).isEqualTo("w".getBytes(StandardCharsets.UTF_8));
    }

    static void mc(String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "mc";
        System.arraycopy(args, 0, command, 1, args.length);
        Container.ExecResult result = minio.execInContainer(command);
        assertThat(result.getExitCode()).as("mc " + String.join(" ", args) + ": " + result.getStderr()).isZero();
    }

    @Test
    void secrets_never_appear_in_failures() throws Exception {
        String wrongSecret = "not-the-secret-value-xyz";
        // A bad signature answers the HEAD pre-check with 403, which proceeds; the failure then
        // surfaces at commit.
        Throwable thrown = catchThrowable(() -> {
            try (var destination = (S3FileDestination) factory(ACCESS, wrongSecret)
                    .build(cfg("no-such-bucket", "", "x.csv"))) {
                destination.open().write('x');
                destination.commit();
            }
        });

        assertThat(thrown).isInstanceOf(FileDestinationException.class)
            .isNotInstanceOf(FileDestinationCollisionException.class);
        assertThat(thrown.getMessage()).contains("no-such-bucket").contains("HTTP 403");
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            assertThat(String.valueOf(t.getMessage())).doesNotContain(wrongSecret, SECRET);
            assertThat(t.toString()).doesNotContain(wrongSecret, SECRET);
        }
        assertThat(thrown.getCause()).isInstanceOf(SdkException.class);
    }

    @Test
    void custom_endpoint_resolves_credentials_through_secret_refs() throws Exception {
        Map<String, String> registry = Map.of("PARTNER_KEY", ACCESS, "PARTNER_SECRET", SECRET);
        var refs = new SecretRefs(name -> Optional.ofNullable(registry.get(name)), name -> null);
        var cfg = new HashMap<>(cfg("secret-refs.csv"));
        cfg.put("access_key_env", "PARTNER_KEY");
        cfg.put("secret_key_env", "PARTNER_SECRET");

        try (var destination = new S3FileDestinationFactory(refs).build(cfg)) {
            destination.open().write('s');
            destination.commit();
        }
        assertThat(exists("secret-refs.csv")).isTrue();
    }
}
