package io.lumnik.hub.connector.transport.sftp;

import io.lumnik.hub.connector.secret.SecretRefs;
import io.lumnik.hub.connector.transport.AfterProcessAction;
import io.lumnik.hub.connector.transport.FileDescriptor;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class SftpFileSourceIT {

    static GenericContainer<?> sftp;
    static int port;
    static final String USER = "lumi";
    static final String PASS = "lumipass";

    @BeforeAll
    static void startSftp() throws Exception {
        sftp = new GenericContainer<>(SftpTestImage.PINNED)
            .withCommand(USER + ":" + PASS + ":1001::upload")
            .withExposedPorts(22)
            // Wait for sshd to LISTEN, not merely for the published port to accept: the
            // docker-proxy binds that port at container start, while the entrypoint is still
            // generating host keys — a connection landing in that window is accepted and then
            // dropped, which is what a bare withExposedPorts(22) races against.
            .waitingFor(Wait.forLogMessage(".*Server listening on 0\\.0\\.0\\.0.*", 1));
        sftp.start();
        port = sftp.getMappedPort(22);

        // Seed 3 files via SSHD client
        try (var helper = new SftpTestHelper("localhost", port, USER, PASS)) {
            helper.put("upload/a.csv", "alpha,1\n");
            helper.put("upload/b.csv", "beta,2\n");
            helper.put("upload/skip.tmp", "ignored\n");
            helper.put("upload/seek.dat", "0123456789ABCDEFGHIJ");
            // Around the glob /upload/stat/*.csv — each one a way to be (or not be) listed.
            helper.put("upload/stat/a.csv", "alpha,1\n");           // listed
            helper.put("upload/stat/empty.csv", "");                 // zero bytes: never listed
            helper.put("upload/stat/note.txt", "note\n");           // glob mismatch
            helper.put("upload/stat/sub/deep.csv", "deep,1\n");     // below the directory: READDIR is flat
            helper.put("upload/stat/dir.csv/in.txt", "in\n");       // a directory named like a match: listed
            helper.symlink("upload/stat/link.csv", "a.csv");        // listed with the LINK's attributes
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
    void stat_returns_exactly_what_the_listing_returns_for_that_path() throws Exception {
        var factory = new SftpFileSourceFactory(SecretRefs.environmentOnly(name -> null));
        var source = (SftpFileSource) factory.build(Map.of(
            "host", "localhost",
            "port", port,
            "user", USER,
            "password_env", "SFTP_PASS_UNSET",
            "path", "/upload/stat/*.csv",
            "strict_host_key", false
        ));
        source.setPasswordOverrideForTest(PASS);

        Map<String, FileDescriptor> listed = source.listSince(Instant.EPOCH)
            .collect(Collectors.toMap(FileDescriptor::path, d -> d));
        assertEquals(Set.of("/upload/stat/a.csv", "/upload/stat/dir.csv", "/upload/stat/link.csv"),
            listed.keySet());
        assertNotEquals(listed.get("/upload/stat/a.csv").size(), listed.get("/upload/stat/link.csv").size(),
            "the listing reports the symlink's own size, so stat must not follow it");

        for (String path : List.of("/upload/stat/a.csv", "/upload/stat/dir.csv", "/upload/stat/link.csv",
                "/upload/stat/empty.csv", "/upload/stat/note.txt", "/upload/stat/sub/deep.csv",
                "/upload/stat/missing.csv", "/upload/stat/sub", "/upload/a.csv", "upload/stat/a.csv")) {
            assertEquals(Optional.ofNullable(listed.get(path)), source.stat(path), path);
        }

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

    // -------------------------------------------------------------------------
    // usable(): is the session still there? (#898)
    // -------------------------------------------------------------------------

    private static SftpFileSource newSource(String glob) {
        var factory = new SftpFileSourceFactory(SecretRefs.environmentOnly(name -> null));
        var source = (SftpFileSource) factory.build(Map.of(
            "host", "localhost",
            "port", port,
            "user", USER,
            "password_env", "SFTP_PASS_UNSET",
            "path", glob,
            "strict_host_key", false
        ));
        source.setPasswordOverrideForTest(PASS);
        return source;
    }

    @Test
    void a_source_that_has_not_connected_yet_is_usable_and_stays_unconnected() {
        try (var source = newSource("/upload/*.csv")) {
            assertTrue(source.usable());
            assertFalse(source.isConnectedForTest(), "asking must not connect");
        }
    }

    @Test
    void a_connected_source_is_usable() {
        try (var source = newSource("/upload/*.csv")) {
            source.listSince(Instant.EPOCH);
            assertTrue(source.isConnectedForTest());
            assertTrue(source.usable());
        }
    }

    @Test
    void a_source_whose_session_died_is_not_usable_and_a_new_one_works() {
        try (var source = newSource("/upload/*.csv")) {
            source.listSince(Instant.EPOCH);
            source.dropConnectionForTest();
            assertFalse(source.usable());
        }
        try (var fresh = newSource("/upload/*.csv")) {
            assertEquals(2, fresh.listSince(Instant.EPOCH).count());
        }
    }

    @Test
    void a_server_that_answers_with_an_error_is_alive() {
        try (var source = newSource("/upload/nothing-here/*.csv")) {
            assertThrows(RuntimeException.class, () -> source.listSince(Instant.EPOCH),
                "the missing directory fails with the server's own answer");
            assertTrue(source.isConnectedForTest());
            assertTrue(source.usable(), "a NO_SUCH_FILE reply proves the session is there");
        }
    }

    @Test
    void a_peer_whose_server_process_died_is_detected() throws Exception {
        try (var source = newSource("/upload/*.csv")) {
            source.listSince(Instant.EPOCH);
            assertTrue(source.usable());

            // Kill the server-side session processes. The kernel closes the socket, so the client may learn it from
            // isOpen() alone: this is the closed-socket case; the silent one is the pause-based test below.
            sftp.execInContainer("sh", "-c", "pkill -u " + USER + " -f 'sshd' || true");

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean dead = false;
            while (System.nanoTime() < deadline && !dead) {
                dead = !source.usable();
                if (!dead) Thread.sleep(200);
            }
            assertTrue(dead, "the probe must notice a session the server dropped");
        }
    }

    /** A frozen server: no reply and no reset, the way a firewall dropping an idle mapping looks to the client. */
    private static void freezeServer() {
        sftp.getDockerClient().pauseContainerCmd(sftp.getContainerId()).exec();
    }

    private static void thawServer() {
        sftp.getDockerClient().unpauseContainerCmd(sftp.getContainerId()).exec();
    }

    @Test
    void a_peer_that_stops_answering_is_detected_within_the_probe_timeout() {
        try (var source = newSource("/upload/*.csv")) {
            source.listSince(Instant.EPOCH);
            source.setProbeTimeoutForTest(Duration.ofSeconds(2));
            freezeServer();
            try {
                // Without a bound the probe waits for MINA's idle timeout (10 minutes by default).
                boolean usable = assertTimeoutPreemptively(Duration.ofSeconds(20), source::usable);
                assertFalse(usable, "a server that never answers is a dead session");
            } finally {
                thawServer();
            }
        }
    }

    @Test
    void an_interrupt_met_by_the_probe_is_kept_and_does_not_condemn_the_session() throws Exception {
        try (var source = newSource("/upload/*.csv")) {
            source.listSince(Instant.EPOCH);
            source.setProbeTimeoutForTest(Duration.ofSeconds(30));
            freezeServer();
            try {
                var result = new AtomicReference<Boolean>();
                var flag = new AtomicBoolean();
                Thread prober = new Thread(() -> {
                    result.set(source.usable());
                    flag.set(Thread.currentThread().isInterrupted());
                });
                prober.start();
                Thread.sleep(500);
                prober.interrupt();                         // a cancel or a shutdown lands while the probe waits
                prober.join(10_000);
                assertFalse(prober.isAlive(), "the probe must give way to the interrupt");
                assertEquals(Boolean.TRUE, result.get(), "an interrupt says nothing about the session: do not rebuild");
                assertTrue(flag.get(), "and the interrupt must still be there for the run to see");
            } finally {
                thawServer();
            }
        }
    }
}
