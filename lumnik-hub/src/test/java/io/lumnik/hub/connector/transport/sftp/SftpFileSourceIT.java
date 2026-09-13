package io.lumnik.hub.connector.transport.sftp;

import io.lumnik.hub.connector.secret.SecretRefs;
import io.lumnik.hub.connector.transport.AfterProcessAction;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SftpFileSourceIT {

    static GenericContainer<?> sftp;
    static int port;
    static final String USER = "lumi";
    static final String PASS = "lumipass";

    @BeforeAll
    static void startSftp() throws Exception {
        sftp = new GenericContainer<>(DockerImageName.parse("atmoz/sftp:alpine"))
            .withCommand(USER + ":" + PASS + ":1001::upload")
            .withExposedPorts(22);
        sftp.start();
        port = sftp.getMappedPort(22);

        // Seed 3 files via SSHD client
        try (var helper = new TestSftpHelper("localhost", port, USER, PASS)) {
            helper.put("upload/a.csv", "alpha,1\n");
            helper.put("upload/b.csv", "beta,2\n");
            helper.put("upload/skip.tmp", "ignored\n");
            helper.put("upload/seek.dat", "0123456789ABCDEFGHIJ");
        }
    }

    @AfterAll
    static void stopSftp() {
        if (sftp != null) sftp.stop();
    }

    @Test
    void list_open_delete_round_trip() throws Exception {
        var factory = new SftpFileSourceFactory(SecretRefs.environmentOnly(name -> null));
        var source = (SftpFileSource) factory.build(Map.of(
            "host", "localhost",
            "port", port,
            "user", USER,
            "password_env", "SFTP_PASS_UNSET",
            "path", "/upload/*.csv",
            "strict_host_key", false
        ));
        source.setPasswordOverrideForTest(PASS);

        var listed = source.listSince(Instant.EPOCH).toList();
        assertEquals(2, listed.size(), "should list both csv files, skip .tmp");

        try (InputStream in = source.open(listed.get(0))) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(content.contains("alpha") || content.contains("beta"));
        }

        source.markProcessed(listed.get(0), AfterProcessAction.DELETE, Map.of());
        var afterDelete = source.listSince(Instant.EPOCH).toList();
        assertEquals(1, afterDelete.size(), "DELETE should have removed one file");

        source.close();
    }

    @Test
    void openAt_uses_a_positioned_handle_read_to_start_exactly_at_the_requested_byte_offset() throws Exception {
        var factory = new SftpFileSourceFactory(SecretRefs.environmentOnly(name -> null));
        var source = (SftpFileSource) factory.build(Map.of(
            "host", "localhost",
            "port", port,
            "user", USER,
            "password_env", "SFTP_PASS_UNSET",
            "path", "/upload/seek.dat",
            "strict_host_key", false
        ));
        source.setPasswordOverrideForTest(PASS);

        var desc = source.listSince(Instant.EPOCH).findFirst().orElseThrow();

        try (InputStream in = source.openAt(desc, 10)) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("ABCDEFGHIJ", content,
                "positioned read must start exactly at byte 10, not the whole file skipped-and-discarded");
        }

        try (InputStream in = source.openAt(desc, 0)) {
            assertEquals("0123456789ABCDEFGHIJ", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }

        assertThrows(IllegalArgumentException.class, () -> source.openAt(desc, -1));

        source.close();
    }

    /** Test-only SFTP client helper. */
    static class TestSftpHelper implements AutoCloseable {
        private final SshClient client;
        private final ClientSession session;

        TestSftpHelper(String host, int port, String user, String password) throws Exception {
            client = SshClient.setUpDefaultClient();
            client.setServerKeyVerifier((s, addr, key) -> true);
            client.start();
            session = client.connect(user, host, port).verify(10_000).getSession();
            session.addPasswordIdentity(password);
            session.auth().verify(10_000);
        }

        void put(String remotePath, String content) throws Exception {
            try (var sftpClient = SftpClientFactory.instance().createSftpClient(session)) {
                int slash = remotePath.lastIndexOf('/');
                if (slash > 0) {
                    String parent = remotePath.substring(0, slash);
                    try {
                        sftpClient.mkdir(parent);
                    } catch (Exception ignored) {}
                }
                try (var out = sftpClient.write(remotePath)) {
                    out.write(content.getBytes(StandardCharsets.UTF_8));
                }
            }
        }

        @Override
        public void close() throws Exception {
            if (session != null) session.close();
            if (client  != null) client.stop();
        }
    }
}
