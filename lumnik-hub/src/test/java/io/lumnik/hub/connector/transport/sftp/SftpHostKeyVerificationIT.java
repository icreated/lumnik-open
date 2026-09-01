package io.lumnik.hub.connector.transport.sftp;

import org.apache.sshd.client.SshClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The host-key contract, exercised through the DEFAULT of {@code strict_host_key} — a path no
 * test had ever run: every one of the eleven existing usages sets it to {@code false}.
 */
class SftpHostKeyVerificationIT {

    static GenericContainer<?> sftp;
    static int port;
    static String actualFingerprint;
    static final String USER = "lumi";
    static final String PASS = "lumipass";
    static final String OTHER_FINGERPRINT = "SHA256:PnqbSTfTnUIsj9hthQnLoFIT3BRkCbegRFVt/1XwEqg";

    @BeforeAll
    static void startSftp() throws Exception {
        sftp = new GenericContainer<>(DockerImageName.parse("atmoz/sftp:alpine"))
            .withCommand(USER + ":" + PASS + ":1001::upload")
            .withExposedPorts(22);
        sftp.start();
        port = sftp.getMappedPort(22);
        try (var helper = new SftpFileSourceIT.TestSftpHelper("localhost", port, USER, PASS)) {
            helper.put("upload/a.csv", "alpha,1\n");
        }
        actualFingerprint = readHostKeyFingerprint();
    }

    static String hostKeyAlgorithm;

    /** Read the container's own host key, so the test compares against the real server. */
    private static String readHostKeyFingerprint() throws Exception {
        var seen = new AtomicReference<String>();
        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.setServerKeyVerifier((s, addr, key) -> {
                seen.set(HostKeyFingerprint.of(key));
                hostKeyAlgorithm = key.getAlgorithm();
                return true;
            });
            client.start();
            try (var session = client.connect(USER, "localhost", port).verify(10_000).getSession()) {
                session.addPasswordIdentity(PASS);
                session.auth().verify(10_000);
            }
        }
        return seen.get();
    }

    @AfterAll
    static void stopSftp() {
        if (sftp != null) sftp.stop();
    }

    /**
     * The container offers RSA host keys too: if Ed25519 support (Bouncy Castle) silently
     * vanished from the classpath, the handshake would fall back to RSA and every green
     * test in this class would be guarding a different mechanism than it claims.
     */
    @Test
    void negotiated_host_key_is_ed25519() {
        assertThat(hostKeyAlgorithm).isIn("Ed25519", "EdDSA");
    }

    private SftpFileSource sourceWith(Map<String, Object> extra) {
        var cfg = new HashMap<String, Object>(Map.of(
            "host", "localhost",
            "port", port,
            "user", USER,
            "password_env", "SFTP_PASS_UNSET",
            "path", "/upload/*.csv"));
        cfg.putAll(extra);
        var factory = new SftpFileSourceFactory((Function<String, String>) name -> null);
        var source = (SftpFileSource) factory.build(cfg);
        source.setPasswordOverrideForTest(PASS);
        return source;
    }

    @Test
    void the_default_refuses_when_no_fingerprint_is_declared() {
        var source = sourceWith(Map.of());   // strict_host_key omitted — the documented default

        assertThatThrownBy(() -> source.listSince(Instant.EPOCH).toList())
            .hasMessageContaining("host key")
            .hasMessageContaining(actualFingerprint)
            .hasMessageContaining("host_key_fingerprint");
    }

    @Test
    void the_declared_fingerprint_lets_the_real_server_through() throws Exception {
        try (var source = sourceWith(Map.of("host_key_fingerprint", actualFingerprint))) {
            assertThat(source.listSince(Instant.EPOCH).toList()).hasSize(1);
        }
    }

    @Test
    void a_fingerprint_from_another_server_is_refused() {
        var source = sourceWith(Map.of("host_key_fingerprint", OTHER_FINGERPRINT));

        assertThatThrownBy(() -> source.listSince(Instant.EPOCH).toList())
            .hasMessageContaining("host key")
            .hasMessageContaining(actualFingerprint);
    }

    @Test
    void a_refused_connect_leaves_no_ssh_client_running() {
        var source = sourceWith(Map.of("host_key_fingerprint", OTHER_FINGERPRINT));
        long before = liveSshdThreads();

        // Refusal is the DEFAULT path now, so a scheduled connector walks it on every tick. Each
        // attempt reassigns sshClient, so a client left started loses its last reference and can
        // never be stopped again.
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> source.listSince(Instant.EPOCH).toList())
                .hasMessageContaining("host key");
        }

        assertThat(liveSshdThreads())
            .as("five refused connects must not accumulate SSH clients")
            .isLessThanOrEqualTo(before + 2);
    }

    private static long liveSshdThreads() {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(t -> t.isAlive() && t.getName().contains("sshd-SshClient"))
            .count();
    }

    @Test
    void an_auth_failure_is_not_blamed_on_the_host_key() {
        var factory = new SftpFileSourceFactory((Function<String, String>) name -> null);
        var cfg = new HashMap<String, Object>(Map.of(
            "host", "localhost",
            "port", port,
            "user", USER,
            "password_env", "SFTP_PASS_UNSET",
            "path", "/upload/*.csv",
            "host_key_fingerprint", actualFingerprint));
        var source = (SftpFileSource) factory.build(cfg);
        source.setPasswordOverrideForTest("not-the-password");

        // The host key is right; the password is not. Reporting this as a host-key refusal would
        // send the integrator to rotate a fingerprint that was never the problem.
        assertThatThrownBy(() -> source.listSince(Instant.EPOCH).toList())
            .hasMessageNotContaining("host key");
    }

    @Test
    void strict_host_key_false_still_accepts_anything_because_it_was_asked_to() throws Exception {
        try (var source = sourceWith(Map.of("strict_host_key", false))) {
            assertThat(source.listSince(Instant.EPOCH).toList()).hasSize(1);
        }
    }
}
