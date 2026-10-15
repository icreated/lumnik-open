package io.lumnik.hub.export.run;

import io.lumnik.hub.connector.transport.sftp.SftpTestHelper;
import io.lumnik.hub.connector.transport.sftp.SftpTestImage;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link ExportRunITSupport}'s scenarios over the #678 SFTP destination (same pinned image). */
@QuarkusTest
class ExportRunSftpIT extends ExportRunScenarios {

    static GenericContainer<?> sftp;
    static int port;
    static String fingerprint;
    static final String USER = "lumi";
    static final String PASS = "lumipass";
    static final String PASS_ENV = SECRET_ENV_PREFIX + "SFTP_PASS";
    static final String WRONG_ENV = SECRET_ENV_PREFIX + "SFTP_WRONG";

    @BeforeAll
    static void startSftp() throws Exception {
        sftp = new GenericContainer<>(SftpTestImage.PINNED)
            .withCommand(USER + ":" + PASS + ":1001::out")
            .withExposedPorts(22)
            .waitingFor(Wait.forLogMessage(".*Server listening on 0\\.0\\.0\\.0.*", 1));
        sftp.start();
        port = sftp.getMappedPort(22);
        fingerprint = SftpTestHelper.readHostKeyFingerprint("localhost", port, USER, PASS);
        // SecretRefs falls back to a JVM system property when the tenant registry has no entry.
        System.setProperty(PASS_ENV, PASS);
        System.setProperty(WRONG_ENV, WRONG_SECRET);
    }

    @AfterAll
    static void stopSftp() {
        System.clearProperty(PASS_ENV);
        System.clearProperty(WRONG_ENV);
        if (sftp != null) sftp.stop();
    }

    static SftpTestHelper helper() throws Exception {
        return new SftpTestHelper("localhost", port, USER, PASS);
    }

    @Override
    String entity() {
        return "ExportRunSftpEntity680";
    }

    @Override
    String destination(String target, boolean wrongCredential) {
        return sftpDestination(target, wrongCredential);
    }

    /** The SFTP destination block, shared with {@link ExportRunSchedulingIT}. */
    static String sftpDestination(String target, boolean wrongCredential) {
        return """
                kind: sftp
                host: localhost
                port: %d
                user: %s
                path: /out/%s
                password_env: %s
                host_key_fingerprint: "%s"
            """.formatted(port, USER, target, wrongCredential ? WRONG_ENV : PASS_ENV, fingerprint)
            .stripTrailing();
    }

    @Override
    byte[] remote(String target) throws Exception {
        return sftpRemote(target);
    }

    static byte[] sftpRemote(String target) throws Exception {
        try (SftpTestHelper h = helper()) {
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
        try (SftpTestHelper h = helper()) {
            assertThat(h.list("/out")).noneMatch(n -> n.endsWith(".part"));
        }
    }

    @Override
    void assertReceipt(ExportRunView run) {
        assertThat(run.etag()).isNull();
        assertThat(run.versionId()).isNull();
    }
}
