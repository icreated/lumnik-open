package io.lumnik.hub.connector.csv;

import io.lumnik.hub.connector.secret.SecretRefs;
import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.transport.FileSource;
import io.lumnik.hub.connector.transport.FileSourceRegistry;
import io.lumnik.hub.connector.transport.s3.S3FileSourceFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lot 5b, task 4 capstone — the live seam proof the fakes cannot give ("a mocked sink cannot
 * prove a sink works"): a multi-chunk remote CSV ingest end-to-end over a REAL minio container,
 * through the REAL {@code S3FileSource} (a {@link io.lumnik.hub.connector.transport.SeekableSource},
 * so resumed windows go through ranged {@code GetObject}).
 *
 * <p>The fixture is sized so the THIRD read's resume anchor lies past the first 8192-byte decode
 * batch — i.e. the run provably issues a ranged GET at a non-zero offset (asserted via the
 * cursor's {@code anchorByte}; byte-exact range semantics themselves are proven by
 * {@code S3FileSourceIT.openAt_uses_a_ranged_getObject...}). What this IT does NOT count is HTTP
 * requests on the wire — there is no proxy in the harness.
 */
class FileSourceCursorReaderS3IT {

    static GenericContainer<?> minio;
    static String endpoint;
    static final String ACCESS = "minio-test";
    static final String SECRET = "minio-test-secret";
    static final String BUCKET = "chunk-bucket";

    static final int N = 250;
    static final int CHUNK = 100;
    /** Padding pushes the 3rd window's resume anchor past the first 8192-byte decode batch. */
    static final String PAD = "x".repeat(64);

    static S3Client admin;

    @BeforeAll
    static void start() {
        minio = new GenericContainer<>("quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z")
            .withEnv("MINIO_ROOT_USER", ACCESS)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET)
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            // Wait for MinIO to SERVE, not merely to listen: the default strategy returns as
            // soon as the port accepts, and MinIO answers 503 for a window after that.
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000).forStatusCode(200));
        minio.start();
        endpoint = "http://localhost:" + minio.getMappedPort(9000);

        admin = S3Client.builder()
            .region(Region.US_EAST_1)
            .endpointOverride(URI.create(endpoint))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(ACCESS, SECRET)))
            .httpClient(UrlConnectionHttpClient.builder().build())
            .forcePathStyle(true)
            .build();
        admin.createBucket(b -> b.bucket(BUCKET));
        admin.putObject(b -> b.bucket(BUCKET).key("chunk/big.csv"),
            RequestBody.fromString(csv(N), StandardCharsets.UTF_8));
        admin.putObject(b -> b.bucket(BUCKET).key("mutant/big.csv"),
            RequestBody.fromString(csv(N), StandardCharsets.UTF_8));
    }

    @AfterAll
    static void stop() {
        if (admin != null) admin.close();
        if (minio != null) minio.stop();
    }

    private static String csv(int n) {
        StringBuilder sb = new StringBuilder("id,name,pad\n");
        for (int i = 1; i <= n; i++) {
            sb.append(i).append(",row-").append(i).append(',').append(PAD).append('\n');
        }
        return sb.toString();
    }

    /** A fresh REAL S3FileSource per read(), exactly the production registry lifecycle. */
    private static FileSourceRegistry registry(String prefix) {
        Function<String, String> env = name -> switch (name) {
            case "AWS_S3_ACCESS_KEY" -> ACCESS;
            case "AWS_S3_SECRET_KEY" -> SECRET;
            default -> null;
        };
        var factory = new S3FileSourceFactory(SecretRefs.environmentOnly(env));
        Map<String, Object> s3cfg = Map.of(
            "kind", "s3",
            "region", "us-east-1",
            "endpoint", endpoint,
            "bucket", BUCKET,
            "prefix", prefix,
            "suffix", ".csv",
            "access_key_env", "AWS_S3_ACCESS_KEY",
            "secret_key_env", "AWS_S3_SECRET_KEY");
        return new FileSourceRegistry() {
            @Override public FileSource build(Map<String, Object> transportCfg) {
                return factory.build(s3cfg);
            }
        };
    }

    private static FileSourceCursorReader reader(String prefix) {
        Map<String, Object> parser = Map.of("encoding", "UTF-8", "delimiter", ",", "has_header", true);
        Map<String, Object> config = Map.of(
            "transport", Map.of("kind", "s3", "bucket", BUCKET, "prefix", prefix),
            "after_process", "leave",
            "parser", parser);
        return new FileSourceCursorReader(config,
            CsvFileConnectorType.buildParserOverrides(parser), registry(prefix));
    }

    @Test
    void multi_chunk_ingest_over_live_minio_is_chunk_bounded_conserving_and_checkpointed() {
        List<Map<String, Object>> all = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        List<JsonNode> cursors = new ArrayList<>();
        ConnectorCursor cursor = ConnectorCursor.empty();
        while (true) {
            FileSourceCursorReader r = reader("chunk/");
            ReadResult<Map<String, Object>> res = r.read(CHUNK, cursor, null);
            assertThat(r.drainMalformed()).isEmpty();
            if (res instanceof ReadResult.HasMore<Map<String, Object>> hm) {
                all.addAll(hm.records());
                if (!hm.records().isEmpty()) sizes.add(hm.records().size());
                cursor = hm.nextCursor();
                cursors.add(cursor.value());
            } else {
                all.addAll(((ReadResult.EndOfStream<Map<String, Object>>) res).records());
                break;
            }
        }

        // Chunk bound + conservation: >= 3 bounded chunks, N rows exactly once, in order.
        assertThat(sizes).containsExactly(100, 100, 50);
        assertThat(all).hasSize(N);
        for (int i = 0; i < N; i++) {
            assertThat(all.get(i).get("id")).isEqualTo(String.valueOf(i + 1));
            assertThat(all.get(i).get("name")).isEqualTo("row-" + (i + 1));
        }

        // The cursor between chunks round-trips (each loop iteration re-decoded the previous
        // JSON through a fresh reader) and carries the checkpoint the fast lane needs.
        JsonNode ip1 = cursors.get(0).path("inProgress");
        assertThat(ip1.isMissingNode()).as("read 1 must leave an in-progress block").isFalse();
        assertThat(ip1.path("path").asText()).isEqualTo("chunk/big.csv");
        assertThat(ip1.path("rowsProcessed").asLong()).isEqualTo(100L);
        assertThat(ip1.path("encoding").asText()).isEqualTo("UTF-8");
        assertThat(ip1.path("delimiter").asText()).isEqualTo(",");
        assertThat(ip1.path("anchorByte").isIntegralNumber()).isTrue();
        assertThat(ip1.path("charDelta").isIntegralNumber()).isTrue();
        assertThat(ip1.path("lineBase").asLong()).isEqualTo(101L);   // header + 100 data lines
        assertThat(ip1.path("headers")).extracting(JsonNode::asText)
            .containsExactly("id", "name", "pad");
        assertThat(ip1.path("size").asLong()).isGreaterThan(8192L);
        assertThat(ip1.path("lastModified").asText()).isNotBlank();
        assertThat(ip1.path("etag").asText()).as("minio listings carry an etag").isNotBlank();

        // Read 3 resumed from a NON-ZERO byte anchor: a ranged GetObject, not a full re-download.
        JsonNode ip2 = cursors.get(1).path("inProgress");
        assertThat(ip2.path("rowsProcessed").asLong()).isEqualTo(200L);
        assertThat(ip2.path("anchorByte").asLong())
            .as("the 200-row boundary lies past the first 8192-byte decode batch")
            .isGreaterThan(0L);
        assertThat(ip2.path("lineBase").asLong()).isEqualTo(201L);

        // The exhausting read promotes the file into watermark/lastBatch and drops inProgress.
        JsonNode last = cursors.get(2);
        assertThat(last.path("inProgress").isMissingNode()).isTrue();
        assertThat(last.path("lastBatch")).extracting(e -> e.path("path").asText())
            .containsExactly("chunk/big.csv");
        assertThat(last.path("watermark").asText()).isNotBlank();
    }

    @Test
    void object_overwritten_between_chunks_fails_loudly_naming_the_key() {
        ReadResult<Map<String, Object>> r1 = reader("mutant/").read(CHUNK, ConnectorCursor.empty(), null);
        assertThat(r1).isInstanceOf(ReadResult.HasMore.class);
        ConnectorCursor c1 = ((ReadResult.HasMore<Map<String, Object>>) r1).nextCursor();

        // Overwrite mid-run with different bytes (different size AND etag).
        admin.putObject(b -> b.bucket(BUCKET).key("mutant/big.csv"),
            RequestBody.fromString(csv(N + 5), StandardCharsets.UTF_8));

        assertThatThrownBy(() -> reader("mutant/").read(CHUNK, c1, null))
            .isInstanceOf(ConnectorException.class)
            .hasMessageContaining("mutant/big.csv")
            .hasMessageContaining("changed mid-ingestion")
            .hasMessageContaining("not resumable");
    }
}
