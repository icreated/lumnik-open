package io.lumnik.hub.export.destination.sftp;

import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.secret.SecretRefs;
import io.lumnik.hub.connector.transport.sftp.SftpAuthMethod;
import io.lumnik.hub.connector.transport.sftp.SftpConnectionFactory;
import io.lumnik.hub.connector.transport.sftp.SftpTestHelper;
import io.lumnik.hub.connector.transport.sftp.SftpTestImage;
import io.lumnik.hub.export.destination.FileDestinationCollisionException;
import io.lumnik.hub.export.destination.FileDestinationException;
import io.lumnik.hub.export.destination.PublishedArtifact;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class SftpFileDestinationIT {

    static GenericContainer<?> sftp;
    static int port;
    static String fingerprint;
    static final String USER = "lumi";
    static final String PASS = "lumipass";

    @BeforeAll
    static void startSftp() throws Exception {
        sftp = new GenericContainer<>(SftpTestImage.PINNED)
            .withCommand(USER + ":" + PASS + ":1001::out")
            .withExposedPorts(22)
            .waitingFor(Wait.forLogMessage(".*Server listening on 0\\.0\\.0\\.0.*", 1));
        sftp.start();
        port = sftp.getMappedPort(22);
        fingerprint = SftpTestHelper.readHostKeyFingerprint("localhost", port, USER, PASS);
    }

    @AfterAll
    static void stopSftp() {
        if (sftp != null) sftp.stop();
    }

    static SftpFileDestinationFactory factory() {
        return new SftpFileDestinationFactory(SecretRefs.environmentOnly(name -> PASS));
    }

    static Map<String, Object> cfg(String path) {
        return Map.of(
            "host", "localhost",
            "port", port,
            "user", USER,
            "password_env", "SFTP_DEST_PASS",
            "path", path,
            "host_key_fingerprint", fingerprint);
    }

    static SftpTestHelper helper() throws Exception {
        return new SftpTestHelper("localhost", port, USER, PASS);
    }

    /** The common shape every commit/abort/collision test checks: no orphaned .part artifact. */
    static void assertNoLeftoverPartFiles(SftpTestHelper helper, String dir) throws Exception {
        assertThat(helper.list(dir)).noneMatch(name -> name.endsWith(".part"));
    }

    @Test
    void commit_publishes_the_complete_artifact_exactly_once() throws Exception {
        try (var destination = (SftpFileDestination) factory().build(cfg("/out/report.csv"))) {
            OutputStream out = destination.open();
            out.write("a,b\n1,2\n".getBytes(StandardCharsets.UTF_8));
            PublishedArtifact receipt = destination.commit();

            assertThat(receipt).isEqualTo(new PublishedArtifact("sftp", "/out/report.csv", null, null));

            try (var helper = helper()) {
                assertThat(helper.get("/out/report.csv")).isEqualTo("a,b\n1,2\n");
                assertNoLeftoverPartFiles(helper, "/out");
            }
        }
    }

    @Test
    void a_second_open_fails_without_touching_the_first_stream() throws Exception {
        try (var destination = (SftpFileDestination) factory().build(cfg("/out/double-open.csv"))) {
            OutputStream out = destination.open();

            assertThatThrownBy(destination::open).isInstanceOf(IllegalStateException.class);

            out.write("still alive".getBytes(StandardCharsets.UTF_8)); // proves the first stream wasn't touched

            destination.abort();
        }
    }

    @Test
    void close_without_commit_behaves_like_abort() throws Exception {
        try (var destination = (SftpFileDestination) factory().build(cfg("/out/close-no-commit.csv"))) {
            OutputStream out = destination.open();
            out.write("never published".getBytes(StandardCharsets.UTF_8));
            destination.close();

            try (var helper = helper()) {
                assertThat(helper.exists("/out/close-no-commit.csv")).isFalse();
                assertNoLeftoverPartFiles(helper, "/out");
            }
        }
    }

    @Test
    void abort_is_idempotent() throws Exception {
        try (var destination = (SftpFileDestination) factory().build(cfg("/out/abort-twice.csv"))) {
            destination.open();
            destination.abort();
            destination.abort(); // must not throw

            // A no-op double-abort alone proves nothing (JUnit only fails on a thrown exception) — the
            // real assertion is that the destination genuinely stayed ABORTED, not just that calling
            // abort() twice happened not to blow up.
            assertThatThrownBy(destination::commit).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void abort_after_commit_throws_and_does_not_unpublish() throws Exception {
        try (var destination = (SftpFileDestination) factory().build(cfg("/out/abort-after-commit.csv"))) {
            OutputStream out = destination.open();
            out.write("published".getBytes(StandardCharsets.UTF_8));
            destination.commit();

            assertThatThrownBy(destination::abort).isInstanceOf(IllegalStateException.class);

            try (var helper = helper()) {
                assertThat(helper.get("/out/abort-after-commit.csv")).isEqualTo("published");
            }
        }
    }

    @Test
    void commit_after_abort_throws() throws Exception {
        try (var destination = (SftpFileDestination) factory().build(cfg("/out/commit-after-abort.csv"))) {
            destination.open();
            destination.abort();

            assertThatThrownBy(destination::commit).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void a_collision_refuses_without_touching_the_existing_file() throws Exception {
        try (var helper = helper()) {
            helper.put("/out/collide.csv", "original\n");
        }
        try (var destination = (SftpFileDestination) factory().build(cfg("/out/collide.csv"))) {
            OutputStream out = destination.open();
            out.write("new-content\n".getBytes(StandardCharsets.UTF_8));

            assertThatThrownBy(destination::commit)
                .isInstanceOf(FileDestinationCollisionException.class);

            try (var helper = helper()) {
                assertThat(helper.get("/out/collide.csv")).isEqualTo("original\n");
                assertNoLeftoverPartFiles(helper, "/out");
            }
        }
    }

    @Test
    void exactly_one_of_two_racing_destinations_publishes_the_same_final_name() throws Exception {
        String path = "/out/race.csv";
        try (var d1 = (SftpFileDestination) factory().build(cfg(path));
             var d2 = (SftpFileDestination) factory().build(cfg(path))) {
            OutputStream o1 = d1.open();
            OutputStream o2 = d2.open();
            o1.write("payload-1\n".getBytes(StandardCharsets.UTF_8));
            o2.write("payload-2\n".getBytes(StandardCharsets.UTF_8));

            var barrier = new CyclicBarrier(2);
            var results = new ConcurrentHashMap<String, Object>();
            Runnable commit1 = () -> {
                try { barrier.await(); d1.commit(); results.put("d1", "ok"); }
                catch (Throwable t) { results.put("d1", t); }
            };
            Runnable commit2 = () -> {
                try { barrier.await(); d2.commit(); results.put("d2", "ok"); }
                catch (Throwable t) { results.put("d2", t); }
            };
            Thread t1 = new Thread(commit1);
            Thread t2 = new Thread(commit2);
            t1.start(); t2.start();
            t1.join(); t2.join();

            long wins = Stream.of(results.get("d1"), results.get("d2"))
                .filter("ok"::equals).count();
            assertThat(wins).as("exactly one of the two racing commits publishes").isEqualTo(1);

            Object loserResult = "ok".equals(results.get("d1")) ? results.get("d2") : results.get("d1");
            assertThat(loserResult).isInstanceOf(FileDestinationCollisionException.class);

            try (var helper = helper()) {
                assertThat(helper.get(path)).matches("payload-[12]\\n");
                assertNoLeftoverPartFiles(helper, "/out");
            }
        }
    }

    @Test
    void a_rename_failure_that_is_not_a_collision_still_refuses_cleanly() throws Exception {
        try (var helper = helper()) {
            helper.put("/out/blocked/.keep", "x");
        }
        try (var destination = (SftpFileDestination) factory().build(cfg("/out/blocked"))) {
            OutputStream out = destination.open();
            out.write("x".getBytes(StandardCharsets.UTF_8));

            assertThatThrownBy(destination::commit)
                .isInstanceOf(FileDestinationException.class)
                .isNotInstanceOf(FileDestinationCollisionException.class);

            try (var helper = helper()) {
                assertThat(helper.list("/out")).contains("blocked");
                assertNoLeftoverPartFiles(helper, "/out");
            }
        }
    }

    /**
     * The wrong-fingerprint refusal doesn't care whether {@code strict_host_key} is present in the
     * config: the factory hardcodes {@code strictHostKey=true} regardless, so a config carrying an
     * explicit {@code strict_host_key: false} must refuse exactly as a config omitting it does —
     * proving the key is genuinely ignored, not merely defaulted.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void a_wrong_host_key_fingerprint_is_refused_before_any_write(boolean explicitStrictHostKeyFalse) {
        var cfg = new HashMap<>(cfg("/out/refused.csv"));
        cfg.put("host_key_fingerprint", "SHA256:PnqbSTfTnUIsj9hthQnLoFIT3BRkCbegRFVt/1XwEqg"); // a real, but wrong, fingerprint
        if (explicitStrictHostKeyFalse) {
            cfg.put("strict_host_key", false);
        }
        assertThatThrownBy(() -> factory().build(cfg))
            .isInstanceOf(ConnectorException.class)
            .hasMessageContaining("host key");
    }

    @Test
    void an_auth_failure_does_not_leak_the_password_or_be_blamed_on_the_host_key() {
        var badFactory = new SftpFileDestinationFactory(SecretRefs.environmentOnly(name -> "not-the-password"));
        var cfg = cfg("/out/auth-fail.csv");

        Throwable thrown = catchThrowable(() -> badFactory.build(cfg));

        assertThat(thrown).as("build() must throw on a bad password").isNotNull();

        // hasMessageNotContaining only inspects getMessage() on the top-level exception; walk the
        // full cause chain so a leak buried in a wrapped cause isn't missed.
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t.getMessage() != null) {
                assertThat(t.getMessage()).doesNotContain(PASS, "not-the-password", "host key");
            }
        }
    }

    @Test
    void a_broken_connection_before_commit_throws_and_leaves_no_final_artifact() throws Exception {
        try (var helper = helper()) {
            helper.put("/out/disc/.keep", "x");
        }
        var connection = SftpConnectionFactory.connect(
            "localhost", port, USER,
            new SftpAuthMethod.Password(PASS),
            true, fingerprint);
        try (var destination = new SftpFileDestination(connection, "/out/disc/disconnected.csv")) {
            OutputStream out = destination.open();
            out.write("partial".getBytes(StandardCharsets.UTF_8));
            connection.close(); // simulate a dropped connection between open() and commit()

            assertThatThrownBy(destination::commit)
                .isInstanceOf(FileDestinationException.class);

            try (var helper = helper()) {
                assertThat(helper.exists("/out/disc/disconnected.csv")).isFalse();
            }
        }
    }

    @Test
    void close_releases_the_underlying_ssh_client() throws Exception {
        long before = liveSshdThreads();

        for (int i = 0; i < 5; i++) {
            try (var destination = (SftpFileDestination) factory().build(cfg("/out/release-" + i + ".csv"))) {
                OutputStream out = destination.open();
                out.write("x".getBytes(StandardCharsets.UTF_8));
                destination.commit();
            }
        }

        assertThat(liveSshdThreads())
            .as("five build+commit+close cycles must not accumulate SSH clients")
            .isLessThanOrEqualTo(before + 2);
    }

    private static long liveSshdThreads() {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(t -> t.isAlive() && t.getName().contains("sshd-SshClient"))
            .count();
    }
}
