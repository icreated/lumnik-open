package io.lumnik.hub.export.run;

import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.lumnik.hub.connector.transport.sftp.SftpTestHelper;
import io.lumnik.hub.connector.transport.sftp.SftpTestImage;
import io.lumnik.hub.testsupport.GatedRelay;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExportAcceptanceScenarios} over the SFTP destination: the pinned server behind a
 * {@link GatedRelay}, its password resolved from each tenant's platform secret registry.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class ExportAcceptanceSftpIT extends ExportAcceptanceScenarios {

    static final String USER = "accept";
    static final String PASS = "sftp-accept-683-password";
    static final String PASS_SECRET = SECRET_NAME_PREFIX + "SFTP_PASS";
    static final String WRONG_SECRET_NAME = SECRET_NAME_PREFIX + "SFTP_WRONG";

    static GenericContainer<?> sftp;
    static GatedRelay relay;
    static String fingerprint;

    @BeforeAll
    static void startSftp() throws Exception {
        sftp = new GenericContainer<>(SftpTestImage.PINNED)
            .withCommand(USER + ":" + PASS + ":1001::out")
            .withExposedPorts(22)
            .waitingFor(Wait.forLogMessage(".*Server listening on 0\\.0\\.0\\.0.*", 1));
        sftp.start();
        fingerprint = SftpTestHelper.readHostKeyFingerprint("localhost", sftp.getMappedPort(22), USER,
            PASS);
        relay = new GatedRelay("localhost", sftp.getMappedPort(22));
    }

    @AfterAll
    static void stopSftp() throws Exception {
        if (relay != null) relay.close();
        if (sftp != null) sftp.stop();
    }

    private static SftpTestHelper direct() throws Exception {
        return new SftpTestHelper("localhost", sftp.getMappedPort(22), USER, PASS);
    }

    @Override
    String entity() {
        return "ExportAcceptSftpEntity683";
    }

    @Override
    GatedRelay relay() {
        return relay;
    }

    @Override
    Map<String, String> secrets() {
        return Map.of(PASS_SECRET, PASS, WRONG_SECRET_NAME, WRONG_SECRET);
    }

    @Override
    String destination(String target, boolean wrongCredential) {
        return """
                kind: sftp
                host: localhost
                port: %d
                user: %s
                path: /out/%s
                password_env: %s
                host_key_fingerprint: "%s"
            """.formatted(relay.port(), USER, target,
                wrongCredential ? WRONG_SECRET_NAME : PASS_SECRET, fingerprint)
            .stripTrailing();
    }

    /** Past the SSH handshake and well into the file, yet far from its end. */
    @Override
    long holdAfterBytes() {
        return 256 * 1024;
    }

    /**
     * ~20 MB of CSV. Far more than the hold: the SSH channel window (2 MB) and the loopback socket
     * buffers keep accepting writes after the relay stops forwarding, so a snapshot that fit in
     * them would be read to its end before the writer ever blocked.
     */
    @Override
    int rowsToOutlastTheHold() {
        return 250_000;
    }

    @Override
    void assertInFlight(String target) throws Exception {
        try (SftpTestHelper h = direct()) {
            assertThat(h.exists("/out/" + target)).as("final path while in flight").isFalse();
        }
        // The temporary sibling exists and the server has already written part of the upload.
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        String sizes;
        do {
            Container.ExecResult r = sftp.execInContainer("sh", "-c",
                "stat -c '%n %s' /home/" + USER + "/out/" + target + ".*.part 2>/dev/null");
            sizes = r.getStdout().trim();
            if (!sizes.isEmpty() && !sizes.endsWith(" 0")) return;
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("no non-empty .part beside " + target + ": '" + sizes + "'");
    }

    @Override
    void placeForeign(String target, byte[] bytes) throws Exception {
        try (SftpTestHelper h = direct()) {
            h.put("/out/" + target, new String(bytes, StandardCharsets.UTF_8));
        }
    }

    @Override
    byte[] remote(String target) throws Exception {
        try (SftpTestHelper h = direct()) {
            String path = "/out/" + target;
            return h.exists(path) ? h.get(path).getBytes(StandardCharsets.UTF_8) : null;
        }
    }

    @Override
    String location(String target) {
        return "/out/" + target;
    }

    @Override
    void assertNoLeftover(String target) throws Exception {
        try (SftpTestHelper h = direct()) {
            assertThat(h.list("/out")).noneMatch(n -> n.endsWith(".part"));
        }
    }
}
