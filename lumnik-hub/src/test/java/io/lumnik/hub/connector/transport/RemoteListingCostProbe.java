package io.lumnik.hub.connector.transport;

import io.lumnik.hub.connector.secret.SecretRefs;
import io.lumnik.hub.connector.transport.s3.S3FileSourceFactory;
import io.lumnik.hub.connector.transport.sftp.SftpFileSource;
import io.lumnik.hub.connector.transport.sftp.SftpFileSourceFactory;
import io.lumnik.hub.connector.transport.sftp.SftpTestHelper;
import io.lumnik.hub.connector.transport.sftp.SftpTestImage;
import io.lumnik.hub.testsupport.MinioTestImage;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * #1017 spike, not a test: what the file-start listing ({@code FileSource.listSince}) of the remote CSV reader costs
 * against the rest of starting a file (a stat + the download of one small file), on a directory of D small files.
 * No {@code Test}/{@code IT} suffix; ask for it by name:
 * {@code mvn test -pl lumnik-hub -Dtest=RemoteListingCostProbe -Dsurefire.failIfNoSpecifiedTests=false}.
 *
 * <p>The listing is measured at its WORST (the whole directory, {@code Instant.EPOCH}): a real run's watermark
 * only shrinks it when mtimes differ, and an archiving run shrinks it by one file per file. Containers are on
 * loopback, so each figure is a FLOOR for the round trips; a WAN multiplies a paged or chunked listing (S3: one
 * request per 1 000 keys; SFTP: one READDIR per ~100 entries) and a stat/GET by the RTT alike.
 */
class RemoteListingCostProbe {

    private static final int[] SIZES = {1_000, 5_000};
    private static final int WARMUP = 3;
    private static final int RUNS = 10;

    @Test
    void measure() throws Exception {
        StringBuilder out = new StringBuilder("\n=== #1017 remote file-start listing, ms, " + RUNS + " runs after " + WARMUP + " warm-ups ===\n");
        s3(out);
        sftp(out);
        System.out.println(out);
    }

    private static void s3(StringBuilder out) throws Exception {
        String access = "minio-test", secret = "minio-test-secret", bucket = "probe";
        try (GenericContainer<?> minio = new GenericContainer<>(MinioTestImage.PINNED)
                .withEnv("MINIO_ROOT_USER", access).withEnv("MINIO_ROOT_PASSWORD", secret)
                .withCommand("server", "/data").withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000).forStatusCode(200))) {
            minio.start();
            String endpoint = "http://localhost:" + minio.getMappedPort(9000);
            try (S3Client admin = S3Client.builder().region(Region.US_EAST_1).endpointOverride(URI.create(endpoint))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret)))
                    .httpClient(UrlConnectionHttpClient.builder().build()).forcePathStyle(true).build()) {
                admin.createBucket(b -> b.bucket(bucket));
                int have = 0;
                for (int d : SIZES) {
                    for (; have < d; have++) {
                        String key = String.format("in/f%06d.csv", have);
                        admin.putObject(b -> b.bucket(bucket).key(key), RequestBody.fromString("id,name\n1,a\n2,b\n"));
                    }
                    Function<String, String> env = n -> n.equals("K") ? access : n.equals("S") ? secret : null;
                    var factory = new S3FileSourceFactory(SecretRefs.environmentOnly(env));
                    Map<String, Object> cfg = Map.of("region", "us-east-1", "endpoint", endpoint, "bucket", bucket,
                        "prefix", "in/", "suffix", ".csv", "access_key_env", "K", "secret_key_env", "S");
                    try (var src = factory.build(cfg)) {
                        report(out, "s3  ", d, src, String.format("in/f%06d.csv", d / 2));
                    }
                }
            }
        }
    }

    private static void sftp(StringBuilder out) throws Exception {
        String user = "lumi", pass = "lumipass";
        try (GenericContainer<?> sftp = new GenericContainer<>(SftpTestImage.PINNED)
                .withCommand(user + ":" + pass + ":1001::upload").withExposedPorts(22)
                .waitingFor(Wait.forLogMessage(".*Server listening on 0\\.0\\.0\\.0.*", 1))) {
            sftp.start();
            int port = sftp.getMappedPort(22);
            var factory = new SftpFileSourceFactory(SecretRefs.environmentOnly(n -> null));
            Map<String, Object> cfg = Map.of("host", "localhost", "port", port, "user", user,
                "password_env", "UNSET", "path", "/upload/*.csv", "strict_host_key", false);
            try (var helper = new SftpTestHelper("localhost", port, user, pass)) {
                int have = 0;
                for (int d : SIZES) {
                    for (; have < d; have++) helper.put(String.format("upload/f%06d.csv", have), "id,name\n1,a\n2,b\n");
                    var src = (SftpFileSource) factory.build(cfg);
                    src.setPasswordOverrideForTest(pass);
                    try (src) {
                        report(out, "sftp", d, src, String.format("/upload/f%06d.csv", d / 2));
                    }
                }
            }
        }
    }

    private static void report(StringBuilder out, String tag, int d, FileSource src, String onePath) throws Exception {
        List<Double> list = time(() -> { try (var s = src.listSince(Instant.EPOCH)) { s.count(); } });
        List<Double> stat = time(() -> src.stat(onePath));
        FileDescriptor fd = src.stat(onePath).orElseThrow();
        List<Double> get = time(() -> { try (var in = src.open(fd)) { in.readAllBytes(); } catch (Exception e) { throw new IllegalStateException(e); } });
        double l = median(list), s = median(stat), g = median(get);
        out.append(String.format("%s D=%5d  listSince(whole dir) %8.2f | stat %6.2f | open+read 1 file %6.2f | listing share of a file start (list/(list+stat+get)) %5.1f%% | run of D files, listings alone %8.0f ms%n",
            tag, d, l, s, g, 100 * l / (l + s + g), l * d));
    }

    private static double median(List<Double> ms) {
        List<Double> sorted = new ArrayList<>(ms);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    private static List<Double> time(Runnable op) {
        for (int i = 0; i < WARMUP; i++) op.run();
        List<Double> ms = new ArrayList<>();
        for (int i = 0; i < RUNS; i++) {
            long t0 = System.nanoTime();
            op.run();
            ms.add((System.nanoTime() - t0) / 1e6);
        }
        return ms;
    }
}
