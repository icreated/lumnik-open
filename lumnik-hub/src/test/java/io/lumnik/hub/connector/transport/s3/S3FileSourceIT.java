package io.lumnik.hub.connector.transport.s3;

import io.lumnik.hub.connector.secret.SecretRefs;
import io.lumnik.hub.connector.transport.AfterProcessAction;
import io.lumnik.hub.connector.transport.FileDescriptor;
import io.lumnik.hub.connector.transport.FileSource;
import io.lumnik.hub.testsupport.MinioTestImage;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class S3FileSourceIT {

    static GenericContainer<?> minio;
    static String endpoint;
    static final String ACCESS = "minio-test";
    static final String SECRET = "minio-test-secret";
    static final String BUCKET = "test-bucket";

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
            for (String key : STAT_SEED) {
                String body = key.endsWith("empty.csv") ? "" : key + ",1\n";
                admin.putObject(b -> b.bucket(BUCKET).key(key), RequestBody.fromString(body, StandardCharsets.UTF_8));
            }
        }
    }

    /** Keys around the prefix {@code stat/} — each one a way to be (or not be) listed. */
    static final List<String> STAT_SEED = List.of(
        "stat/a.csv",            // listed
        "stat/a.csv.d/x.csv",    // listed too (an S3 listing is recursive), and has stat/a.csv as its prefix
        "stat/dir.csv/in.csv",   // listed, while stat/dir.csv itself does not exist
        "stat/empty.csv",        // zero bytes: never listed
        "stat/note.txt",         // suffix mismatch
        "statx/a.csv");          // outside the prefix

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
        var factory = new S3FileSourceFactory(SecretRefs.environmentOnly(env));
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
    void stat_returns_exactly_what_the_listing_returns_for_that_key() {
        try (var source = statSource(ACCESS, SECRET)) {
            assertStatAgreesWithListing(source);
        }
    }

    /**
     * A least-privilege policy that pins {@code s3:prefix} to the configured prefix refuses the
     * listing narrowed to one key; stat must then answer from the configured listing, as before
     * #898, instead of failing every resumed chunk.
     */
    @Test
    void stat_falls_back_to_the_configured_listing_when_the_narrowed_one_is_refused() throws Exception {
        String policy = """
            {"Version":"2012-10-17","Statement":[
             {"Effect":"Allow","Action":["s3:ListBucket"],"Resource":["arn:aws:s3:::%s"],
              "Condition":{"StringEquals":{"s3:prefix":["stat/"]}}},
             {"Effect":"Allow","Action":["s3:GetObject"],"Resource":["arn:aws:s3:::%s/*"]}]}"""
            .formatted(BUCKET, BUCKET);
        mc("alias", "set", "local", "http://localhost:9000", ACCESS, SECRET);
        mc("admin", "user", "add", "local", "lister", "lister-secret");
        minio.copyFileToContainer(Transferable.of(policy), "/tmp/prefix-pinned.json");
        mc("admin", "policy", "create", "local", "prefix-pinned", "/tmp/prefix-pinned.json");
        mc("admin", "policy", "attach", "local", "prefix-pinned", "--user", "lister");

        try (S3Client lister = client("lister", "lister-secret")) {
            S3Exception refused = assertThrows(S3Exception.class,
                () -> lister.listObjectsV2(b -> b.bucket(BUCKET).prefix("stat/a.csv")));
            assertEquals(403, refused.statusCode(), "the policy must refuse the narrowed listing");
        }
        try (var source = statSource("lister", "lister-secret")) {
            assertStatAgreesWithListing(source);
        }
    }

    /** #1382: the same key two nights running is archived twice — a copy onto an existing key would replace it. */
    @Test
    void move_archives_each_night_under_its_own_name() {
        FileSource source = statSource(ACCESS, SECRET);
        S3Client admin = client(ACCESS, SECRET);
        Map<String, Object> cfg = Map.of("archive_prefix", "nightly-archive/");
        for (int night = 1; night <= 2; night++) {
            admin.putObject(b -> b.bucket(BUCKET).key("stat/nightly.csv"), RequestBody.fromString("id\n1\n", StandardCharsets.UTF_8));
            FileDescriptor read = source.stat("stat/nightly.csv").orElseThrow();
            source.markProcessed(new FileDescriptor(read.path(), read.size(), read.lastModified().plusSeconds(night * 86400L),
                read.etag()), AfterProcessAction.MOVE, cfg);
        }
        List<String> archived = admin.listObjectsV2(b -> b.bucket(BUCKET).prefix("nightly-archive/")).contents()
            .stream().map(o -> o.key()).sorted().toList();
        assertEquals(2, archived.size(), "two nights, two archives: " + archived);
        assertTrue(archived.stream().allMatch(k -> k.matches("nightly-archive/nightly\\.\\d{8}T\\d{6}(\\.\\d{3}|\\.\\d{9})?Z\\.csv")), archived.toString());
        assertTrue(source.stat("stat/nightly.csv").isEmpty(), "the source key is gone");
    }

    private static void assertStatAgreesWithListing(FileSource source) {
        Map<String, FileDescriptor> listed = source.listSince(Instant.EPOCH)
            .collect(Collectors.toMap(FileDescriptor::path, d -> d));
        assertEquals(Set.of("stat/a.csv", "stat/a.csv.d/x.csv", "stat/dir.csv/in.csv"), listed.keySet());

        // Same descriptor, field for field (size, lastModified, etag): the in-progress cursor was
        // written from a listing and is compared against this stat with sameVersion.
        List<String> probes = new ArrayList<>(STAT_SEED);
        probes.addAll(List.of("stat/dir.csv", "stat/missing.csv", "other/c.csv"));
        for (String key : probes) {
            assertEquals(Optional.ofNullable(listed.get(key)), source.stat(key), key);
        }
    }

    private static FileSource statSource(String access, String secret) {
        Function<String, String> env = name -> switch (name) {
            case "AWS_S3_ACCESS_KEY" -> access;
            case "AWS_S3_SECRET_KEY" -> secret;
            default -> null;
        };
        return new S3FileSourceFactory(SecretRefs.environmentOnly(env)).build(Map.of(
            "region", "us-east-1",
            "endpoint", endpoint,
            "bucket", BUCKET,
            "prefix", "stat/",
            "suffix", ".csv",
            "access_key_env", "AWS_S3_ACCESS_KEY",
            "secret_key_env", "AWS_S3_SECRET_KEY"
        ));
    }

    private static S3Client client(String access, String secret) {
        return S3Client.builder()
            .region(Region.US_EAST_1)
            .endpointOverride(URI.create(endpoint))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret)))
            .httpClient(UrlConnectionHttpClient.builder().build())
            .forcePathStyle(true)
            .build();
    }

    private static void mc(String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "mc";
        System.arraycopy(args, 0, command, 1, args.length);
        Container.ExecResult result = minio.execInContainer(command);
        assertEquals(0, result.getExitCode(), "mc " + String.join(" ", args) + ": " + result.getStderr());
    }

    @Test
    void openAt_uses_a_ranged_getObject_to_start_exactly_at_the_requested_byte_offset() throws Exception {
        Function<String, String> env = name -> switch (name) {
            case "AWS_S3_ACCESS_KEY" -> ACCESS;
            case "AWS_S3_SECRET_KEY" -> SECRET;
            default -> null;
        };
        var factory = new S3FileSourceFactory(SecretRefs.environmentOnly(env));
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
