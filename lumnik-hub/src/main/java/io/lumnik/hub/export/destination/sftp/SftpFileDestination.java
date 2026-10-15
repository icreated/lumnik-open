package io.lumnik.hub.export.destination.sftp;

import io.lumnik.hub.connector.transport.sftp.SftpConnection;
import io.lumnik.hub.export.destination.FileDestination;
import io.lumnik.hub.export.destination.FileDestinationCollisionException;
import io.lumnik.hub.export.destination.FileDestinationException;
import io.lumnik.hub.export.destination.PublishedArtifact;
import org.apache.sshd.sftp.common.SftpException;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Writes a uniquely-named {@code .part} file in the same directory as the final path, then
 * publishes it with a bare atomic, no-clobber SFTP rename on {@link #commit()}. See the design
 * doc / issue #678 for why no {@code SftpClient.CopyMode} is ever passed to that rename.
 */
public class SftpFileDestination implements FileDestination {

    private enum State { READY, OPEN, COMMITTED, ABORTED }

    private final SftpConnection connection;
    private final String finalPath;
    private final String tempPath;

    private State state = State.READY;
    private OutputStream stream;

    SftpFileDestination(SftpConnection connection, String finalPath) {
        this.connection = connection;
        this.finalPath = finalPath;
        this.tempPath = tempPartPath(finalPath);
    }

    /** Same directory as finalPath — same-filesystem is what makes the eventual rename atomic;
     *  the UUID is what keeps two concurrent runs from sharing or cleaning up each other's file. */
    private static String tempPartPath(String finalPath) {
        int slash = finalPath.lastIndexOf('/');
        String dir = slash >= 0 ? finalPath.substring(0, slash + 1) : "";
        String name = slash >= 0 ? finalPath.substring(slash + 1) : finalPath;
        return dir + name + "." + UUID.randomUUID() + ".part";
    }

    @Override
    public synchronized OutputStream open() {
        if (state != State.READY)
            throw new IllegalStateException("open() called twice, or after commit/abort/close: state=" + state);
        try {
            stream = connection.sftp().write(tempPath);
        } catch (IOException e) {
            state = State.ABORTED;
            cleanupPart();
            throw new FileDestinationException("SFTP destination: failed to open " + tempPath, e);
        }
        state = State.OPEN;
        return new WriteFailures(stream, tempPath);
    }

    /**
     * What {@link #open()} hands out: a write or flush the server refuses surfaces as the unchecked
     * {@link FileDestinationException}, like every other failure this destination owns, so a caller
     * tells an upload failure from a failure of whatever produced the bytes by type alone. Closing
     * is not wrapped: {@link #commit()}/{@link #abort()} close the raw stream themselves.
     */
    private static final class WriteFailures extends FilterOutputStream {
        private final String tempPath;

        WriteFailures(OutputStream out, String tempPath) {
            super(out);
            this.tempPath = tempPath;
        }

        @Override
        public void write(int b) {
            try {
                out.write(b);
            } catch (IOException e) {
                throw failed(e);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) {
            try {
                out.write(b, off, len); // straight through, not FilterOutputStream's byte loop
            } catch (IOException e) {
                throw failed(e);
            }
        }

        @Override
        public void flush() {
            try {
                out.flush();
            } catch (IOException e) {
                throw failed(e);
            }
        }

        private FileDestinationException failed(IOException e) {
            return new FileDestinationException("SFTP destination: write to " + tempPath + " failed", e);
        }
    }

    @Override
    public synchronized PublishedArtifact commit() {
        if (state != State.OPEN)
            throw new IllegalStateException("commit() requires an open, uncommitted destination: state=" + state);
        try {
            stream.close();
        } catch (IOException e) {
            state = State.ABORTED;
            cleanupPart();
            throw new FileDestinationException(
                "SFTP destination: failed to close " + tempPath + " — not renamed, nothing published", e);
        }
        try {
            connection.sftp().rename(tempPath, finalPath);
        } catch (IOException e) {
            state = State.ABORTED;
            cleanupPart();
            if (isCollision(e)) {
                throw new FileDestinationCollisionException(
                    "SFTP destination: " + finalPath + " already exists — refusing to overwrite"
                        + " a previously delivered artifact", e);
            }
            throw new FileDestinationException(
                "SFTP destination: rename " + tempPath + " -> " + finalPath + " failed", e);
        }
        state = State.COMMITTED;
        return new PublishedArtifact("sftp", finalPath, null, null);
    }

    /**
     * The SFTP protocol version real servers negotiate (v3 — including the OpenSSH-based
     * atmoz/sftp image these ITs run against) has no dedicated "already exists" rename status; a
     * failed no-clobber rename comes back as the same generic {@code SSH_FX_FAILURE} whether the
     * target is an existing file, an existing directory, or something else — empirically both a
     * file-vs-file collision and a rename onto an existing directory were observed returning that
     * identical generic status, so status alone cannot tell them apart. A genuine collision is
     * told apart from every other rename failure by what a follow-up {@code lstat} finds at
     * finalPath instead: a pre-existing regular file means somebody already delivered there;
     * anything else is a different kind of failure. The probe is best-effort — if it cannot
     * complete (the connection that just failed the rename may itself be unusable), the original
     * failure is reported as-is rather than replaced by a probing error. Empirical basis:
     * {@code SftpFileDestinationIT#a_collision_refuses_without_touching_the_existing_file} and
     * {@code SftpFileDestinationIT#a_rename_failure_that_is_not_a_collision_still_refuses_cleanly}.
     *
     * <p>This is not a purely one-directional approximation. It is not only capable of
     * under-classifying a real collision as a plain failure (a probe failure reports "not a
     * collision" even when one may have occurred) — a rename that fails for an unrelated reason
     * (e.g. a permissions error) while a regular file happens to already sit at finalPath for some
     * other reason is reported as a collision too, a false positive by the letter of "this rename
     * attempt failed because finalPath already existed". That misclassification is still benign:
     * whatever caused THIS rename to fail, a regular file already occupies finalPath, so refusing
     * to clobber it is the correct outcome regardless of why the attempt failed.
     */
    private boolean isCollision(IOException renameFailure) {
        if (!(renameFailure instanceof SftpException)) return false;
        try {
            return connection.sftp().lstat(finalPath).isRegularFile();
        } catch (Exception probeFailure) {
            return false;
        }
    }

    @Override
    public synchronized void abort() {
        if (state == State.ABORTED) return;
        if (state == State.COMMITTED)
            throw new IllegalStateException("abort() called after commit() already published the artifact");
        if (stream != null) {
            try { stream.close(); } catch (IOException ignored) {}
        }
        if (state == State.OPEN) cleanupPart();
        state = State.ABORTED;
    }

    /** Best-effort: removes only the .part this instance owns. A cleanup failure must never mask
     *  the primary failure that triggered it. */
    private void cleanupPart() {
        try {
            connection.sftp().remove(tempPath);
        } catch (IOException ignored) {
        }
    }

    @Override
    public synchronized void close() {
        try {
            if (state != State.COMMITTED) abort();
        } finally {
            connection.close();
        }
    }
}
