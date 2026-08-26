package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
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
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: CSV connector engine run against a real MinIO (S3-compatible) container.
 * Mirrors the MinIO setup from {@code CsvIngestServiceRemoteTest} and the connector harness
 * from {@code CsvSftpConnectorIT}.
 */
@QuarkusTest
class CsvS3ConnectorIT {

    static GenericContainer<?> minio;
    static String endpoint;
    static final String ACCESS = "c1b-s3-access";
    static final String SECRET = "c1b-s3-secret";
    static final String BUCKET = "c1b-s3-test";

    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject UserTransaction utx;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @BeforeAll
    static void start() {
        minio = new GenericContainer<>("minio/minio:RELEASE.2025-09-07T16-13-09Z")
            .withEnv("MINIO_ROOT_USER", ACCESS)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET)
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            // Wait for MinIO to SERVE, not merely to listen. The default strategy returns as
            // soon as the port accepts a connection, and MinIO answers 503 for a window after
            // that — so the first S3 call below races its readiness, and loses under the load
            // of a full-reactor run.
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000).forStatusCode(200));
        minio.start();
        // getHost() — not a hardcoded "localhost": Testcontainers may run on a remote Docker host
        // where the mapped port is exposed on the daemon's host, not localhost.
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
                RequestBody.fromString("row_id,email\n1,a@x\n2,b@x\n", StandardCharsets.UTF_8));
        }

        // Expose credentials via system properties so S3FileSourceFactory resolves them.
        System.setProperty("__INLINE_S3_ACCESS", ACCESS);
        System.setProperty("__INLINE_S3_SECRET", SECRET);
    }

    @AfterAll
    static void stop() {
        System.clearProperty("__INLINE_S3_ACCESS");
        System.clearProperty("__INLINE_S3_SECRET");
        if (minio != null) minio.stop();
    }

    private long scalar(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) { rs.next(); return rs.getLong(1); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void s3_connector_run_ingests_remote_csv() {
        Map<String, Object> transport = Map.of(
            "kind",           "s3",
            "region",         "us-east-1",
            "endpoint",       endpoint,
            "bucket",         BUCKET,
            "prefix",         "data/",
            "suffix",         ".csv",
            "access_key_env", "__INLINE_S3_ACCESS",
            "secret_key_env", "__INLINE_S3_SECRET"
        );

        UUID id = TenantContext.callAsSystem(() -> {
            try {
                utx.begin();
                var c = new Connector();
                c.setTenantId(1L); c.setName("c1b-s3-" + System.nanoTime()); c.setTypeId("csv-file");
                c.setConfig(Map.of("kind", "csv-file", "transport", transport,
                    "parser", Map.of("has_header", true)));
                connectors.persist(c); connectors.flush();
                var ep = new ConnectorEndpoint();
                ep.setConnectorId(c.getId()); ep.setTenantId(1L); ep.setName("default");
                ep.setMethod("GET"); ep.setPath("/data/*.csv"); ep.setEnabled(true);
                ep.setConfig(Map.of("name", "c1b_s3"));
                endpoints.persist(ep);
                utx.commit();
                return c.getId();
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        RunStatus status = engine.runSync(id, new RunTrigger.Manual(1L));
        assertThat(status).isInstanceOf(RunStatus.Completed.class);
        assertThat(scalar("SELECT count(*) FROM connector.t_c1b_s3")).isEqualTo(2L);
    }
}
