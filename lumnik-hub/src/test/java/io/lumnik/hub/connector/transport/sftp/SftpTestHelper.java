package io.lumnik.hub.connector.transport.sftp;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test-only SFTP client helper, shared by every SFTP IT — reader and writer side alike — so the
 * raw MINA bootstrap used to seed/inspect the test container's filesystem lives in exactly one
 * place. Extracted from the class that used to be SftpFileSourceIT's private nested
 * {@code TestSftpHelper}, with no behavior change to the methods that already existed.
 */
public final class SftpTestHelper implements AutoCloseable {

    private final SshClient client;
    private final ClientSession session;
    private final SftpClient sftpClient;

    public SftpTestHelper(String host, int port, String user, String password) throws Exception {
        client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier((s, addr, key) -> true);
        client.start();
        session = client.connect(user, host, port).verify(10_000).getSession();
        session.addPasswordIdentity(password);
        session.auth().verify(10_000);
        // One SFTP subsystem channel for the helper's whole lifetime, not one per call — a caller
        // like SftpFileSourceIT's @BeforeAll issues several put()s back to back on one instance.
        sftpClient = SftpClientFactory.instance().createSftpClient(session);
    }

    public void put(String remotePath, String content) throws Exception {
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

    public void symlink(String linkPath, String targetPath) throws Exception {
        sftpClient.symLink(linkPath, targetPath);
    }

    public String get(String remotePath) throws Exception {
        try (var in = sftpClient.read(remotePath)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public boolean exists(String remotePath) throws Exception {
        try {
            sftpClient.lstat(remotePath);
            return true;
        } catch (SftpException e) {
            if (e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE) return false;
            throw e;
        }
    }

    public List<String> list(String dir) throws Exception {
        List<String> names = new ArrayList<>();
        for (var entry : sftpClient.readDir(dir)) {
            if (entry.getFilename().equals(".") || entry.getFilename().equals("..")) continue;
            names.add(entry.getFilename());
        }
        return names;
    }

    /** Reads the container's own host key fingerprint, so a test compares against the real server
     *  instead of a hardcoded value. */
    public static String readHostKeyFingerprint(String host, int port, String user, String password) throws Exception {
        var seen = new AtomicReference<String>();
        try (SshClient probe = SshClient.setUpDefaultClient()) {
            probe.setServerKeyVerifier((s, addr, key) -> {
                seen.set(HostKeyFingerprint.of(key));
                return true;
            });
            probe.start();
            try (var session = probe.connect(user, host, port).verify(10_000).getSession()) {
                session.addPasswordIdentity(password);
                session.auth().verify(10_000);
            }
        }
        return seen.get();
    }

    @Override
    public void close() throws Exception {
        if (sftpClient != null) sftpClient.close();
        if (session != null) session.close();
        if (client != null) client.stop();
    }
}
