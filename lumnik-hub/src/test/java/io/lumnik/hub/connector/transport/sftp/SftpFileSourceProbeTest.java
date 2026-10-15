package io.lumnik.hub.connector.transport.sftp;

import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedChannelException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@link SftpFileSource#usable()} concludes from a failed probe. MINA raises {@link SftpException} for the
 * server's application answers AND for {@code NO_CONNECTION} / {@code CONNECTION_LOST}, so the type alone proves
 * nothing: the status decides.
 */
class SftpFileSourceProbeTest {

    @Test
    void only_an_application_status_proves_the_server_is_alive() {
        for (int status : new int[] {
                SftpConstants.SSH_FX_NO_SUCH_FILE, SftpConstants.SSH_FX_NO_SUCH_PATH,
                SftpConstants.SSH_FX_PERMISSION_DENIED, SftpConstants.SSH_FX_FAILURE,
                SftpConstants.SSH_FX_OP_UNSUPPORTED}) {
            assertThat(SftpFileSource.answered(new SftpException(status, "answered " + status)))
                .as("status %d is the server's reply", status).isTrue();
        }
        for (int status : new int[] {SftpConstants.SSH_FX_NO_CONNECTION, SftpConstants.SSH_FX_CONNECTION_LOST}) {
            assertThat(SftpFileSource.answered(new SftpException(status, "gone " + status)))
                .as("status %d says the session is gone", status).isFalse();
        }
        for (IOException dead : new IOException[] {
                new IOException("broken pipe"), new ClosedChannelException(), new SocketTimeoutException("read timed out")}) {
            assertThat(SftpFileSource.answered(dead)).as(dead.getClass().getSimpleName()).isFalse();
        }
    }
}
