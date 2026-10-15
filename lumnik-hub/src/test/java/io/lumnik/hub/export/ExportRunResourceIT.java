package io.lumnik.hub.export;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.auth.NoBypassOidcProfile;
import io.lumnik.hub.connector.transport.sftp.SftpTestHelper;
import io.lumnik.hub.export.run.ArtifactName;
import io.lumnik.hub.connector.transport.sftp.SftpTestImage;
import io.lumnik.hub.testsupport.EntitySourceTableFixture;
import io.lumnik.hub.testsupport.YamlBody;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * The four {@code /api/exports/{name}/runs} doors over a real SFTP destination, under real bearer
 * JWTs (dev-bypass off) so the integrator gate and the scope refusal are actually exercised.
 */
@QuarkusTest
@TestProfile(NoBypassOidcProfile.class)
class ExportRunResourceIT {

    private static final String ENTITY = "ExportRunResourceEntity680";
    private static final String TABLE = "t_exportrunresource680_erp";
    private static final String USER = "lumi";
    private static final String PASS = "lumipass";
    private static final String PASS_ENV = "EXPORT_RUN_RESOURCE_IT_SFTP_PASS";

    static GenericContainer<?> sftp;
    static int port;
    static String fingerprint;

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @BeforeAll
    static void startSftp() throws Exception {
        sftp = new GenericContainer<>(SftpTestImage.PINNED)
            .withCommand(USER + ":" + PASS + ":1001::out")
            .withExposedPorts(22)
            .waitingFor(Wait.forLogMessage(".*Server listening on 0\\.0\\.0\\.0.*", 1));
        sftp.start();
        port = sftp.getMappedPort(22);
        fingerprint = SftpTestHelper.readHostKeyFingerprint("localhost", port, USER, PASS);
        System.setProperty(PASS_ENV, PASS);
    }

    @AfterAll
    static void stopSftp() {
        System.clearProperty(PASS_ENV);
        if (sftp != null) sftp.stop();
    }

    private static String integratorSales() {
        return NoBypassOidcProfile.token(Set.of("lm_integrator", "scope:sales"), null);
    }

    private static String adminSales() {
        return NoBypassOidcProfile.token(Set.of("lm_admin", "scope:sales"), null);
    }

    private static String integratorMagasin() {
        return NoBypassOidcProfile.token(Set.of("lm_integrator", "scope:magasin"), null);
    }

    private static String userOnly() {
        return NoBypassOidcProfile.token(Set.of("lm_user"), null);
    }

    @BeforeEach
    void seedEntity() throws Exception {
        EntitySourceTableFixture.create(adminDs, TABLE, "v_exportrunresourceentity680", "cust_id", "cust_name");
        given().config(YamlBody.CONFIG).header("Authorization", "Bearer " + adminSales())
            .contentType("application/yaml").body("""
                kind: Entity
                name: %s
                match: [code]
                scopes: [sales]
                sources:
                  - table: %s
                    map:
                      cust_id: code
                      cust_name: name
                """.formatted(ENTITY, TABLE))
            .post("/api/entities/apply")
            .then().statusCode(200);
        Jdbc.update(adminDs, "seed " + TABLE,
            "INSERT INTO connector." + TABLE + " (tenant_id, cust_id, cust_name) "
                + "VALUES (1, '1', 'Ann'), (1, '2', 'Bob'), (1, '3', 'Cid')",
            ps -> {});
    }

    /** Applies an export writing to {@code /out/<target>}; returns its name. */
    private String applyExport(String target) {
        String name = "export-runs-" + UUID.randomUUID().toString().substring(0, 8);
        given().config(YamlBody.CONFIG).header("Authorization", "Bearer " + integratorSales())
            .contentType("application/yaml").body("""
                apiVersion: apps.lumnik.io/v1
                kind: Export
                metadata:
                  name: %s
                spec:
                  scope: sales
                  source:
                    entity: %s
                  columns: [code, name]
                  order: [code]
                  format:
                    kind: csv
                  destination:
                    kind: sftp
                    host: localhost
                    port: %d
                    user: %s
                    path: /out/%s
                    password_env: %s
                    host_key_fingerprint: "%s"
                  mode: snapshot
                """.formatted(name, ENTITY, port, USER, target, PASS_ENV, fingerprint))
            .post("/api/exports/apply")
            .then().statusCode(200);
        return name;
    }

    private static Response post(String token, String path) {
        return given().header("Authorization", "Bearer " + token).post(path);
    }

    private static String start(String name) {
        return post(integratorSales(), "/api/exports/" + name + "/runs")
            .then().statusCode(202).body("runId", notNullValue())
            .extract().path("runId");
    }

    /** Polls the GET until the run leaves Running (bounded: 60 s); returns the final body. */
    private static Response awaitTerminal(String name, String runId) throws InterruptedException {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (true) {
            Response r = given().header("Authorization", "Bearer " + integratorSales())
                .get("/api/exports/" + name + "/runs/" + runId);
            r.then().statusCode(200);
            if (!"Running".equals(r.path("status"))) return r;
            assertThat(System.nanoTime()).as("run %s still Running after 60 s", runId).isLessThan(deadline);
            Thread.sleep(100);
        }
    }

    @Test
    void aStartedRunIsAcceptedThenCompletesAndIsListed() throws Exception {
        String name = applyExport("resource-ok.csv");
        String runId = start(name);

        Response done = awaitTerminal(name, runId);
        assertThat((String) done.path("status")).isEqualTo("Completed");
        assertThat((String) done.path("location")).isEqualTo("/out/resource-ok.csv");
        assertThat((Integer) done.path("rowCount")).isEqualTo(3);
        assertThat((Integer) done.path("triggeredBy")).isEqualTo(1);

        given().header("Authorization", "Bearer " + integratorSales())
            .get("/api/exports/" + name + "/runs?limit=0")
            .then().statusCode(200)
            .body("size()", equalTo(1))
            .body("[0].id", equalTo(runId));
    }

    @Test
    void twoConcurrentStartsGiveOneAcceptedAndOneConflict() throws Exception {
        String name = applyExport("resource-concurrent-${timestamp}.csv");
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<Response> call = () -> {
            barrier.await();
            return post(integratorSales(), "/api/exports/" + name + "/runs");
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Response> answers = new ArrayList<>();
        try {
            Future<Response> a = pool.submit(call);
            Future<Response> b = pool.submit(call);
            answers.add(a.get());
            answers.add(b.get());
        } finally {
            pool.shutdownNow();
        }
        assertThat(answers).extracting(Response::statusCode).containsExactlyInAnyOrder(202, 409);
        Response conflict = answers.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow();
        assertThat((String) conflict.path("error"))
            .isEqualTo("export '" + name + "' already has a run in flight");
        String runId = answers.stream().filter(r -> r.statusCode() == 202).findFirst().orElseThrow()
            .path("runId");
        awaitTerminal(name, runId);
    }

    @Test
    void startAndRetryAcceptTheExportNameInAnyCase() throws Exception {
        // The store resolves names case-insensitively, so every door does: a start and a retry
        // addressed as EXPORT-RUNS-… must run the stored export, never refuse or 500.
        String dir = "cased-" + UUID.randomUUID().toString().substring(0, 8);
        String name = applyExport(dir + "/resource-cased.csv");
        String cased = name.toUpperCase(Locale.ROOT);

        String first = post(integratorSales(), "/api/exports/" + cased + "/runs")
            .then().statusCode(202).extract().path("runId");
        assertThat((String) awaitTerminal(cased, first).path("status")).isEqualTo("Failed");

        sftp.execInContainer("mkdir", "-p", "/home/" + USER + "/out/" + dir);
        sftp.execInContainer("chown", "1001", "/home/" + USER + "/out/" + dir);
        String retried = post(integratorSales(), "/api/exports/" + cased + "/runs/" + first + "/retry")
            .then().statusCode(202).extract().path("runId");
        Response done = awaitTerminal(cased, retried);
        assertThat((String) done.path("status")).isEqualTo("Completed");
        assertThat((String) done.path("export")).isEqualTo(name);
    }

    @Test
    void retryingACompletedRunIsAConflict() throws Exception {
        String name = applyExport("resource-completed.csv");
        String runId = start(name);
        assertThat((String) awaitTerminal(name, runId).path("status")).isEqualTo("Completed");

        post(integratorSales(), "/api/exports/" + name + "/runs/" + runId + "/retry")
            .then().statusCode(409);
    }

    @Test
    void aRetriedFailedRunPublishesUnderTheOriginalArtifactName() throws Exception {
        // The first attempt fails (its directory does not exist yet); the retry must resolve
        // ${timestamp} against the ORIGINAL artifact time, not the moment it was retried.
        String dir = "later-" + UUID.randomUUID().toString().substring(0, 8);
        String template = dir + "/resource-${timestamp}.csv";
        String name = applyExport(template);
        String first = start(name);
        Response failed = awaitTerminal(name, first);
        assertThat((String) failed.path("status")).isEqualTo("Failed");
        Instant artifactTime = artifactTime(first);

        sftp.execInContainer("mkdir", "-p", "/home/" + USER + "/out/" + dir);
        sftp.execInContainer("chown", "1001", "/home/" + USER + "/out/" + dir);
        Thread.sleep(1_100); // a fresh "now" would now resolve to a different second

        String retried = post(integratorSales(), "/api/exports/" + name + "/runs/" + first + "/retry")
            .then().statusCode(202).extract().path("runId");
        Response done = awaitTerminal(name, retried);
        assertThat((String) done.path("status")).isEqualTo("Completed");
        assertThat((Integer) done.path("attempt")).isEqualTo(2);
        assertThat((String) done.path("retryOf")).isEqualTo(first);
        assertThat(artifactTime(retried)).isEqualTo(artifactTime);
        String location = done.path("location");
        assertThat(location).isEqualTo("/out/" + ArtifactName.resolve(template, artifactTime));
        try (SftpTestHelper h = new SftpTestHelper("localhost", port, USER, PASS)) {
            byte[] bytes = h.get(location).getBytes(StandardCharsets.UTF_8);
            assertThat((String) done.path("sha256")).isEqualTo(
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        }

        // The retried run is Completed now: it cannot be retried again.
        post(integratorSales(), "/api/exports/" + name + "/runs/" + retried + "/retry")
            .then().statusCode(409);
    }

    private Instant artifactTime(String runId) {
        return Jdbc.queryOne(adminDs, "artifactTime " + runId,
                "SELECT artifact_time FROM connector.export_run WHERE id = ?::uuid",
                ps -> ps.setString(1, runId),
                rs -> rs.getTimestamp(1).toInstant())
            .orElseThrow(() -> new AssertionError("no export_run row " + runId));
    }

    @Test
    void anUngrantedScopeIsRefusedOnAllFourDoors() throws Exception {
        String name = applyExport("resource-scope.csv");
        String base = "/api/exports/" + name + "/runs";
        String someId = UUID.randomUUID().toString();
        String token = integratorMagasin();
        post(token, base).then().statusCode(403).body("error", equalTo("scope not granted"));
        given().header("Authorization", "Bearer " + token).get(base)
            .then().statusCode(403).body("error", equalTo("scope not granted"));
        given().header("Authorization", "Bearer " + token).get(base + "/" + someId)
            .then().statusCode(403).body("error", equalTo("scope not granted"));
        post(token, base + "/" + someId + "/retry")
            .then().statusCode(403).body("error", equalTo("scope not granted"));

        // A refusal is not a run that failed later: nothing was claimed, read or published.
        assertThat(Jdbc.queryOne(adminDs, "runs " + name,
            "SELECT count(*) FROM connector.export_run WHERE export_name = ?",
            ps -> ps.setString(1, name), rs -> rs.getLong(1))).contains(0L);
        assertThat(Jdbc.queryOne(adminDs, "egress " + name,
            "SELECT count(*) FROM core.audit_outbox WHERE entity_key = ? AND operation = 'EGRESS'",
            ps -> ps.setString(1, name), rs -> rs.getLong(1))).contains(0L);
        try (SftpTestHelper h = new SftpTestHelper("localhost", port, USER, PASS)) {
            assertThat(h.exists("/out/resource-scope.csv")).isFalse();
        }
    }

    @Test
    void aNonIntegratorIsRefused() {
        String name = applyExport("resource-role.csv");
        String base = "/api/exports/" + name + "/runs";
        post(userOnly(), base).then().statusCode(403);
        given().header("Authorization", "Bearer " + userOnly()).get(base).then().statusCode(403);
    }

    @Test
    void unknownExportAndForeignRunAreNotFound() {
        String name = applyExport("resource-404.csv");
        post(integratorSales(), "/api/exports/no-such-export-680/runs").then().statusCode(404);
        given().header("Authorization", "Bearer " + integratorSales())
            .get("/api/exports/" + name + "/runs/" + UUID.randomUUID())
            .then().statusCode(404);
        given().header("Authorization", "Bearer " + integratorSales())
            .get("/api/exports/" + name + "/runs/not-a-uuid")
            .then().statusCode(400);
    }
}
