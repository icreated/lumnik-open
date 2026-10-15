package io.lumnik.hub.export.run;

import io.lumnik.hub.testsupport.GatedRelay;
import io.lumnik.hub.testsupport.MinioTestImage;
import io.lumnik.hub.testsupport.StallEvidence;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.MultipartUpload;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.net.URI;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExportAcceptanceScenarios} over the S3 destination — the pinned MinIO behind a
 * {@link GatedRelay}, its keys resolved from each tenant's platform secret registry — plus what
 * only S3 has: a multipart upload to hold mid-stream, and an ETag that must never be mistaken for
 * the content hash.
 */
@QuarkusTest
@TestProfile(BoundedSnapshotProfile.class)
class ExportAcceptanceS3IT extends ExportAcceptanceScenarios {

    static final String ACCESS = "minio-accept";
    static final String SECRET = "minio-accept-683-secret";
    static final String BUCKET = "export-accept-bucket";
    static final String PREFIX = "accept";
    static final String ACCESS_NAME = SECRET_NAME_PREFIX + "S3_ACCESS";
    static final String SECRET_NAME = SECRET_NAME_PREFIX + "S3_SECRET";
    static final String WRONG_NAME = SECRET_NAME_PREFIX + "S3_WRONG";
    /** The destination's part size (S3UploadStream.DEFAULT_PART_SIZE): the unit S3 memory is in. */
    static final long PART = 8L * 1024 * 1024;

    @Inject @ConfigProperty(name = BoundedSnapshotProfile.SNAPSHOT_TIMEOUT_PROPERTY)
    int snapshotTimeoutSeconds;

    static GenericContainer<?> minio;
    static GatedRelay relay;
    static S3Client admin;

    @BeforeAll
    static void startMinio() throws Exception {
        minio = new GenericContainer<>(MinioTestImage.PINNED)
            .withEnv("MINIO_ROOT_USER", ACCESS)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET)
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000).forStatusCode(200));
        minio.start();
        admin = S3Client.builder()
            .region(Region.US_EAST_1)
            .endpointOverride(URI.create("http://localhost:" + minio.getMappedPort(9000)))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS, SECRET)))
            .httpClient(UrlConnectionHttpClient.builder().build())
            .forcePathStyle(true)
            .build();
        admin.createBucket(b -> b.bucket(BUCKET));
        relay = new GatedRelay("localhost", minio.getMappedPort(9000));
    }

    @AfterAll
    static void stopMinio() throws Exception {
        if (relay != null) relay.close();
        if (admin != null) admin.close();
        if (minio != null) minio.stop();
    }

    @Override
    String entity() {
        return "ExportAcceptS3Entity683";
    }

    /** The relay's state, the store's last words and how loaded the machine was when the wait gave up. */
    @Override
    String stallEvidence() {
        StringBuilder out = new StringBuilder(super.stallEvidence());
        out.append("\nhost: ").append(Runtime.getRuntime().availableProcessors()).append(" cpus, load ")
            .append(StallEvidence.hostLoad());
        out.append("\nminio: ").append(minio == null ? "(not started)" : StallEvidence.tail(minio.getLogs(), 30));
        return out.toString();
    }

    @Override
    GatedRelay relay() {
        return relay;
    }

    @Override
    Map<String, String> secrets() {
        return Map.of(ACCESS_NAME, ACCESS, SECRET_NAME, SECRET, WRONG_NAME, WRONG_SECRET);
    }

    @Override
    String destination(String target, boolean wrongCredential) {
        return """
                kind: s3
                bucket: %s
                prefix: %s
                region: us-east-1
                endpoint: http://localhost:%d
                filename: %s
                access_key_env: %s
                secret_key_env: %s
            """.formatted(BUCKET, PREFIX, relay.port(), target, ACCESS_NAME,
                wrongCredential ? WRONG_NAME : SECRET_NAME)
            .stripTrailing();
    }

    /** Part 1 is stored, part 2 is on the wire. */
    @Override
    long holdAfterBytes() {
        return PART + PART / 2;
    }

    /** ~32 MB of CSV: two parts are consumed when the hold engages, half the snapshot is not. */
    @Override
    int rowsToOutlastTheHold() {
        return 400_000;
    }

    private static String key(String target) {
        return PREFIX + "/" + target;
    }

    @Override
    void assertInFlight(String target) throws Exception {
        assertThat(head(target)).as("final object while in flight").isNull();
        List<MultipartUpload> uploads =
            admin.listMultipartUploads(b -> b.bucket(BUCKET).prefix(key(target))).uploads();
        assertThat(uploads).hasSize(1);
        String uploadId = uploads.getFirst().uploadId();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (admin.listParts(b -> b.bucket(BUCKET).key(key(target)).uploadId(uploadId)).parts().isEmpty()) {
            if (System.nanoTime() > deadline) throw new AssertionError("no stored part for " + target);
            Thread.sleep(100);
        }
    }

    @Override
    void placeForeign(String target, byte[] bytes) {
        admin.putObject(b -> b.bucket(BUCKET).key(key(target)), RequestBody.fromBytes(bytes));
    }

    @Override
    byte[] remote(String target) {
        try {
            return admin.getObjectAsBytes(b -> b.bucket(BUCKET).key(key(target))).asByteArray();
        } catch (NoSuchKeyException e) {
            return null;
        }
    }

    private static HeadObjectResponse head(String target) {
        try {
            return admin.headObject(b -> b.bucket(BUCKET).key(key(target)));
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return null;
            throw e;
        }
    }

    @Override
    String location(String target) {
        return "s3://" + BUCKET + "/" + key(target);
    }

    @Override
    void assertNoLeftover(String target) {
        assertThat(admin.listMultipartUploads(b -> b.bucket(BUCKET).prefix(key(target))).uploads())
            .isEmpty();
    }

    // ------------------------------------------------------------------ S3 only

    /** The profile is what bounds a stalled run's read here: assert it is the one in effect. */
    @Test
    void theSnapshotReadIsBoundedByTheDedicatedProfile() {
        assertThat(snapshotTimeoutSeconds)
            .isEqualTo(BoundedSnapshotProfile.SNAPSHOT_TIMEOUT_SECONDS);
    }

    @Test
    void aSinglePartReceiptKeepsTheContentHashApartFromTheEtag() throws Exception {
        String target = unique("receipt") + ".csv";
        String name = applyStandard(target);

        ExportRunView run = run(name, AT);

        byte[] bytes = remote(target);
        HeadObjectResponse head = head(target);
        assertThat(run.status()).isEqualTo(ExportRunStatus.COMPLETED);
        assertThat(run.location()).isEqualTo(location(target));
        // The content hash is SHA-256 of the bytes, and the object says so in its own metadata ...
        assertThat(run.sha256()).isEqualTo(sha256(bytes)).isEqualTo(head.metadata().get("lumnik-sha256"));
        // ... while the ETag is the store's identity for the object, recorded as-is, and not it.
        assertThat(run.etag()).isEqualTo(head.eTag()).doesNotContain("-").doesNotContain(run.sha256());
    }

    /**
     * ~64 MB of CSV through the runner, held with its third part on the wire. At that moment the
     * hub must be holding a part buffer and a JDBC fetch batch — not the snapshot: the retained
     * heap is measured there, against a baseline, and must stay under the artifact's own size.
     *
     * <p>The bound sits between two measured regimes, far from both. Streaming retains the part
     * buffer, the SDK's copy of the part in flight and ordinary runtime noise: 16–35 MB observed,
     * depending on the JVM and platform. A snapshot pulled into memory whole (a dropped fetch size,
     * an un-enlisted readonly connection, a buffering encoder) retains every row as driver tuples
     * and Java strings: ~160 MB observed for this ~65 MB artifact. One artifact of retained heap
     * leaves ~30 MB of headroom above the first and fails the second by ~95 MB.
     *
     * <p>The completed receipt is then checked for what a multipart object makes easy to get
     * wrong: its ETag is a hash of part hashes with a {@code -N} suffix, so the content's SHA-256
     * comes from the bytes themselves and the object carries no single-part checksum metadata.
     */
    @Test
    void aLargeExportStreamsInBoundedMemoryAndIsReceiptedByContentHash() throws Exception {
        int rows = 800_000;
        bulk(rows);
        String target = unique("large") + ".csv";
        String name = applyStandard(target);

        long baseline = retainedHeap();
        relay.holdAfter(2 * PART + PART / 2);
        ExportRunner.Launch launch;
        long held;
        try {
            launch = launchAs(TENANT, name, AT);
            awaitHeld();
            held = retainedHeap();
        } finally {
            relay.release();
        }
        ExportRunView run = awaitAs(TENANT, name, launch);

        assertThat(run.status()).isEqualTo(ExportRunStatus.COMPLETED);
        assertThat(run.rowCount()).isEqualTo(rows + 3L);
        assertThat(run.byteCount()).isGreaterThan(6 * PART);
        assertThat(held - baseline)
            .as("heap retained mid-stream by a %d-byte export", run.byteCount())
            .isLessThan(run.byteCount());

        HeadObjectResponse head = head(target);
        long parts = (run.byteCount() + PART - 1) / PART;
        assertThat(head.contentLength()).isEqualTo(run.byteCount());
        assertThat(streamedSha256(target)).isEqualTo(run.sha256());
        assertThat(run.etag()).isEqualTo(head.eTag()).matches("\"[0-9a-f]{32}-" + parts + "\"")
            .doesNotContain(run.sha256());
        assertThat(head.metadata()).doesNotContainKey("lumnik-sha256");
        assertNoLeftover(target);
    }

    /** The heap still in use after full collections: what the JVM actually retains. */
    private static long retainedHeap() throws InterruptedException {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        for (int i = 0; i < 3; i++) {
            memory.gc();
            Thread.sleep(100);
        }
        return memory.getHeapMemoryUsage().getUsed();
    }

    private static String streamedSha256(String target) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (ResponseInputStream<GetObjectResponse> in =
                 admin.getObject(b -> b.bucket(BUCKET).key(key(target)))) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) digest.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
