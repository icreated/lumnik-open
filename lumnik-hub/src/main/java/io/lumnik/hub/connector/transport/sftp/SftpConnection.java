package io.lumnik.hub.connector.transport.sftp;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;

/**
 * One fully-authenticated, host-key-verified SFTP connection, owning the three resources that
 * make it up together: {@code SshClient} (its own I/O threads), the {@code ClientSession} built
 * on it, and the {@code SftpClient} built on that session. Returning only the innermost of the
 * three (as the original SftpFileSource#ensureConnected effectively did, via its own fields)
 * would make the SshClient's ownership invisible to a caller and reopen the exact thread-leak
 * risk SftpHostKeyVerificationIT#a_refused_connect_leaves_no_ssh_client_running already guards
 * on the reader side. SftpFileSource and SftpFileDestination (#678) each hold one of these, with
 * an equally explicit lifecycle.
 */
public final class SftpConnection implements AutoCloseable {

    private final SshClient sshClient;
    private final ClientSession session;
    private final SftpClient sftp;

    SftpConnection(SshClient sshClient, ClientSession session, SftpClient sftp) {
        this.sshClient = sshClient;
        this.session = session;
        this.sftp = sftp;
    }

    public SftpClient sftp() {
        return sftp;
    }

    /** Closes all three resources, best-effort and in order, regardless of which ones exist —
     *  safe to call on a partially-constructed connection and safe to call twice. */
    @Override
    public void close() {
        try { if (sftp != null) sftp.close(); } catch (Exception ignored) {}
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (sshClient != null) sshClient.stop(); } catch (Exception ignored) {}
    }
}
